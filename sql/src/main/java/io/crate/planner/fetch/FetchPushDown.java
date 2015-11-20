/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.planner.fetch;

import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import io.crate.Constants;
import io.crate.analyze.OrderBy;
import io.crate.analyze.QuerySpec;
import io.crate.analyze.relations.DocTableRelation;
import io.crate.analyze.relations.QueriedDocTable;
import io.crate.analyze.symbol.*;
import io.crate.metadata.DocReferenceConverter;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.ScoreReferenceDetector;
import io.crate.metadata.doc.DocSysColumns;
import io.crate.types.DataTypes;

import javax.annotation.Nullable;
import java.util.*;

public class FetchPushDown {

    private final QuerySpec querySpec;
    private final DocTableRelation docTableRelation;
    private static final InputColumn DOCID_COL = new InputColumn(0, DataTypes.LONG);
    private LinkedHashMap<ReferenceIdent, FetchReference> fetchReferences;
    private LinkedHashMap<ReferenceIdent, FetchReference> partitionReferences;

    public FetchPushDown(QuerySpec querySpec, DocTableRelation docTableRelation) {
        this.querySpec = querySpec;
        this.docTableRelation = docTableRelation;
    }

    public InputColumn docIdCol() {
        return DOCID_COL;
    }

    public Collection<Reference> fetchRefs() {
        if (fetchReferences == null || fetchReferences.isEmpty()) {
            return ImmutableList.of();
        }
        return Collections2.transform(fetchReferences.values(), FetchReference.REF_FUNCTION);
    }

    private FetchReference allocateReference(Reference ref) {
        RowGranularity granularity = ref.info().granularity();
        if (granularity == RowGranularity.DOC) {
            if (!ref.ident().columnIdent().isSystemColumn()) {
                ref = DocReferenceConverter.toSourceLookup(ref);
            }
            if (fetchReferences == null) {
                fetchReferences = new LinkedHashMap<>();
            }
            return toFetchReference(ref, fetchReferences);
        } else {
            assert docTableRelation.tableInfo().isPartitioned() && granularity == RowGranularity.PARTITION;
            if (partitionReferences == null) {
                partitionReferences = new LinkedHashMap<>(docTableRelation.tableInfo().partitionedBy().size());
            }
            return toFetchReference(ref, partitionReferences);
        }
    }

    private FetchReference toFetchReference(Reference ref, LinkedHashMap<ReferenceIdent, FetchReference> refs) {
        FetchReference fRef = refs.get(ref.ident());
        if (fRef == null) {
            fRef = new FetchReference(DOCID_COL, ref);
            refs.put(ref.ident(), fRef);
        }
        return fRef;
    }

    @Nullable
    public QueriedDocTable pushDown() {
        assert !querySpec.groupBy().isPresent() && !querySpec.having().isPresent() && !querySpec.hasAggregates();

        Optional<OrderBy> orderBy = querySpec.orderBy();

        FetchRequiredVisitor.Context context;
        if (orderBy.isPresent()) {
            context = new FetchRequiredVisitor.Context(new LinkedHashSet<>(querySpec.orderBy().get().orderBySymbols()));
        } else {
            context = new FetchRequiredVisitor.Context();

        }

        boolean fetchRequired = FetchRequiredVisitor.INSTANCE.process(querySpec.outputs(), context);
        if (!fetchRequired) return null;

        // build the subquery
        QuerySpec sub = new QuerySpec();
        Reference docIdReference = new Reference(DocSysColumns.forTable(docTableRelation.tableInfo().ident(), DocSysColumns.DOCID));

        List<Symbol> outputs = new ArrayList<>();
        if (orderBy.isPresent()) {
            sub.orderBy(orderBy.get());
            outputs.add(docIdReference);
            outputs.addAll(context.querySymbols());
        } else {
            outputs.add(docIdReference);
        }
        for (Symbol symbol : querySpec.outputs()) {
            if (ScoreReferenceDetector.detect(symbol) && !outputs.contains(symbol)) {
                outputs.add(symbol);
            }
        }
        sub.outputs(outputs);
        QueriedDocTable subRelation = new QueriedDocTable(docTableRelation, sub);
        HashMap<Symbol, InputColumn> symbolMap = new HashMap<>(sub.outputs().size());

        int index = 0;
        for (Symbol symbol : sub.outputs()) {
            symbolMap.put(symbol, new InputColumn(index++, symbol.valueType()));
        }

        // push down the where clause
        sub.where(querySpec.where());
        querySpec.where(null);

        ToFetchReferenceVisitor toFetchReferenceVisitor = new ToFetchReferenceVisitor();
        toFetchReferenceVisitor.processInplace(querySpec.outputs(), symbolMap);

        if (orderBy.isPresent()) {
            // replace order by symbols with input columns, we need to copy the order by since it was pushed down to the
            // subquery before
            List<Symbol> newOrderBySymbols = MappingSymbolVisitor.copying().process(orderBy.get().orderBySymbols(), symbolMap);
            querySpec.orderBy(new OrderBy(newOrderBySymbols, orderBy.get().reverseFlags(), orderBy.get().nullsFirst()));
        }

        sub.limit(querySpec.limit().or(Constants.DEFAULT_SELECT_LIMIT) + querySpec.offset());
        return subRelation;
    }

    private class ToFetchReferenceVisitor extends MappingSymbolVisitor {

        private ToFetchReferenceVisitor() {
            super(false);
        }

        @Override
        public Symbol visitReference(Reference symbol, Map<? extends Symbol, ? extends Symbol> context) {
            Symbol mapped = context.get(symbol);
            if (mapped != null) {
                return mapped;
            }
            return allocateReference(symbol);
        }
    }

}
