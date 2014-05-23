/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import io.crate.core.StringUtils;
import io.crate.core.collections.StringObjectMaps;
import io.crate.exceptions.ColumnValidationException;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.ReferenceInfo;
import io.crate.metadata.TableIdent;
import io.crate.planner.symbol.Reference;
import io.crate.planner.symbol.Symbol;
import io.crate.sql.tree.Expression;
import io.crate.sql.tree.Insert;
import io.crate.sql.tree.Table;
import io.crate.sql.tree.ValuesList;
import org.apache.lucene.util.BytesRef;

import javax.annotation.Nullable;
import java.util.*;

public class InsertStatementAnalyzer extends DataStatementAnalyzer<InsertAnalysis> {

    @Override
    public Symbol visitInsert(Insert node, InsertAnalysis context) {
        process(node.table(), context);

        if (context.table().isAlias() && !context.table().isPartitioned()) {
            throw new IllegalArgumentException("Table alias not allowed in INSERT statement.");
        }
        int maxValuesLength = node.maxValuesLength();
        int numColumns = node.columns().size() == 0 ? maxValuesLength : node.columns().size();
        // allocate columnsLists
        context.columns(new ArrayList<Reference>(numColumns));

        if (node.columns().size() == 0) { // no columns given in statement

            if (maxValuesLength > context.table().columns().size()) {
                throw new IllegalArgumentException("too many values");
            }

            int i = 0;
            for (ReferenceInfo columnInfo : context.table().columns()) {
                if (i >= maxValuesLength) { break; }
                addColumn(columnInfo.ident().columnIdent().name(), context, i);
                i++;
            }

        } else {
            if (maxValuesLength > node.columns().size()) {
                throw new IllegalArgumentException("too few values");
            }
            for (int i = 0; i < node.columns().size(); i++) {
                addColumn(node.columns().get(i), context, i);
            }
        }

        if (!context.table().hasAutoGeneratedPrimaryKey() && context.primaryKeyColumnIndices().size() == 0) {
            throw new IllegalArgumentException("Primary key is required but is missing from the insert statement");
        }
        ColumnIdent clusteredBy = context.table().clusteredBy();
        if (clusteredBy != null && !clusteredBy.name().equalsIgnoreCase("_id") && context.routingColumnIndex() < 0) {
            throw new IllegalArgumentException("Clustered by value is required but is missing from the insert statement");
        }

        for (ValuesList valuesList : node.valuesLists()) {
            process(valuesList, context);
        }

        return null;
    }

    private Reference addColumn(String column, InsertAnalysis context, int i) {
        assert context.table() != null;
        return addColumn(new ReferenceIdent(context.table().ident(), column), context, i);
    }

    private Reference addColumn(ReferenceIdent ident, InsertAnalysis context, int i) {
        final ColumnIdent columnIdent = ident.columnIdent();
        Preconditions.checkArgument(!columnIdent.name().startsWith("_"), "Inserting system columns is not allowed");

        // set primary key index if found
        if (Iterables.any(context.table().primaryKey(), new Predicate<ColumnIdent>() {
            @Override
            public boolean apply(@Nullable ColumnIdent input) {
                return input != null && input.getRoot().equals(columnIdent);
            }
        })) {
            context.addPrimaryKeyColumnIdx(i);
        }

        // set routing if found
        ColumnIdent routing = context.table().clusteredBy();
        if (routing != null && (routing.equals(columnIdent) || routing.isChildOf(columnIdent))) {
            context.routingColumnIndex(i);
        }

        // set partitioned column if found
        if (Iterables.any(context.table().partitionedBy(), new Predicate<ColumnIdent>() {
            @Override
            public boolean apply(@Nullable ColumnIdent input) {
                return input != null && input.getRoot().equals(columnIdent);
            }
        })) {
            context.addPartitionedByIndex(i);
        }

        // ensure that every column is only listed once
        Reference columnReference = context.allocateUniqueReference(ident);

        context.columns().add(columnReference);

        return columnReference;
    }

    @Override
    protected Symbol visitTable(Table node, InsertAnalysis context) {
        Preconditions.checkState(context.table() == null, "inserting into multiple tables is not supported");
        context.editableTable(TableIdent.of(node));
        return null;
    }

    @Override
    public Symbol visitValuesList(ValuesList node, InsertAnalysis context) {

        List<String> primaryKeyValues = new ArrayList<>(context.table().primaryKey().size());
        Map<String, Object> sourceMap = new HashMap<>(node.values().size());
        String routingValue = null;

        if (node.values().size() != context.columns().size()) {
            throw new IllegalArgumentException("incorrect number of values");
        }

        if (context.table().isPartitioned()) {
            context.newPartitionMap();
        }

        int i = 0;
        for (Expression expression : node.values()) {
            // TODO: instead of doing a type guessing and then a conversion this could
            // be improved by using the dataType from the column Reference as a hint
            Symbol valuesSymbol = process(expression, context);

            // implicit type conversion
            Reference column = context.columns().get(i);
            final ColumnIdent columnIdent = column.info().ident().columnIdent();

            try {
                valuesSymbol = context.normalizeInputForReference(valuesSymbol, column);
            } catch (IllegalArgumentException|UnsupportedOperationException e) {
                throw new ColumnValidationException(column.info().ident().columnIdent().fqn(), e);
            }

            try {
                Object value = ((io.crate.operation.Input)valuesSymbol).value();
                if (value instanceof BytesRef) {
                    value = ((BytesRef) value).utf8ToString();
                }
                if (context.primaryKeyColumnIndices().contains(i)) {
                    int idx = context.table().primaryKey().indexOf(columnIdent);
                    if (idx < 0) {
                        // oh look, one or more nested primary keys!
                        assert value instanceof Map;
                        Map<String, Object> mapValue = (Map<String, Object>) value;
                        for (ColumnIdent primaryKeyIdent : Iterables.filter(context.table().primaryKey(), new Predicate<ColumnIdent>() {
                            @Override
                            public boolean apply(@Nullable ColumnIdent input) {
                                return input != null && input.getRoot().equals(columnIdent);
                            }
                        })) {
                            int pkIdx = context.table().primaryKey().indexOf(primaryKeyIdent);
                            Object nestedPkValue = StringObjectMaps.getByPath(mapValue, StringUtils.PATH_JOINER.join(primaryKeyIdent.path()));
                            addPrimaryKeyValue(pkIdx, nestedPkValue, primaryKeyValues);
                        }
                    } else {
                        addPrimaryKeyValue(idx, value, primaryKeyValues);
                    }
                }
                if (i == context.routingColumnIndex()) {
                    routingValue = extractRoutingValue(columnIdent, value, context);
                }
                if (context.partitionedByIndices().contains(i)) {
                    Object rest = processPartitionedByValues(columnIdent, value, context);
                    if (rest != null) {
                        sourceMap.put(columnIdent.name(), rest);
                    }
                } else {
                    sourceMap.put(
                            columnIdent.name(),
                            value
                    );
                }
            } catch (ClassCastException e) {
                // symbol is no input
                throw new ColumnValidationException(columnIdent.name(),
                        String.format("invalid value '%s' in insert statement", valuesSymbol.toString()));
            }

            i++;
        }
        context.sourceMaps().add(sourceMap);
        context.addIdAndRouting(primaryKeyValues, routingValue);

        return null;
    }

    private void addPrimaryKeyValue(int index, Object value, List<String> primaryKeyValues) {
        if (value == null) {
            throw new IllegalArgumentException("Primary key value must not be NULL");
        }
        if (primaryKeyValues.size() > index) {
            primaryKeyValues.add(index, value.toString());
        } else {
            primaryKeyValues.add(value.toString());
        }
    }

    private String extractRoutingValue(ColumnIdent columnIdent, Object columnValue, InsertAnalysis context) {
        Object clusteredByValue = columnValue;
        ColumnIdent clusteredByIdent = context.table().clusteredBy();
        if (!columnIdent.equals(clusteredByIdent)) {
            // oh my gosh! A nested clustered by value!!!
            assert clusteredByValue instanceof Map;
            clusteredByValue = StringObjectMaps.getByPath(
                    (Map<String, Object>)clusteredByValue,
                    StringUtils.PATH_JOINER.join(clusteredByIdent.path()));
        }
        if (clusteredByValue == null) {
            throw new IllegalArgumentException("Clustered by value must not be NULL");
        }

        return clusteredByValue.toString();
    }

    private Object processPartitionedByValues(final ColumnIdent columnIdent, Object columnValue, InsertAnalysis context) {
        int idx = context.table().partitionedBy().indexOf(columnIdent);
        Map<String, String> partitionMap = context.currentPartitionMap();
        if (idx < 0) {
            assert columnValue instanceof Map;
            Map<String, Object> mapValue = (Map<String, Object>) columnValue;
            // hmpf, one or more nested partitioned by columns
            for (ColumnIdent partitionIdent : Iterables.filter(context.table().partitionedBy(), new Predicate<ColumnIdent>() {
                @Override
                public boolean apply(@Nullable ColumnIdent input) {
                    return input != null && input.getRoot().equals(columnIdent);
                }
            })) {
                Object nestedValue = mapValue.remove(StringUtils.PATH_JOINER.join(partitionIdent.path()));
                if (nestedValue instanceof BytesRef) {
                    nestedValue = ((BytesRef) nestedValue).utf8ToString();
                }
                if (partitionMap != null) {
                    partitionMap.put(partitionIdent.fqn(), nestedValue != null ? nestedValue.toString() : null);
                }
            }

            // put the rest into source
            return mapValue;
        } else if (partitionMap != null) {
            partitionMap.put(columnIdent.name(), columnValue != null ? columnValue.toString() : null);
        }
        return null;
    }
}
