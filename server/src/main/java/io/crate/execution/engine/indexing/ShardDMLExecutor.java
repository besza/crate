/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.engine.indexing;

import com.google.common.base.Throwables;
import io.crate.action.FutureActionListener;
import io.crate.action.LimitedExponentialBackoff;
import io.crate.data.BatchIterator;
import io.crate.data.BatchIterators;
import io.crate.data.CollectionBucket;
import io.crate.data.Row;
import io.crate.data.Row1;
import io.crate.execution.dml.ShardRequest;
import io.crate.execution.dml.ShardResponse;
import io.crate.execution.engine.collect.CollectExpression;
import io.crate.execution.jobs.NodeJobsCounter;
import io.crate.execution.support.RetryListener;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.cluster.service.ClusterService;
import org.apache.logging.log4j.LogManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static io.crate.execution.jobs.NodeJobsCounter.MAX_NODE_CONCURRENT_OPERATIONS;

public class ShardDMLExecutor<TReq extends ShardRequest<TReq, TItem>,
                              TItem extends ShardRequest.Item,
                              TAcc,
                              TResult extends Iterable<? extends Row>>
    implements Function<BatchIterator<Row>, CompletableFuture<? extends Iterable<? extends Row>>> {

    private static final Logger LOGGER = LogManager.getLogger(ShardDMLExecutor.class);

    private static final BackoffPolicy BACKOFF_POLICY = LimitedExponentialBackoff.limitedExponential(1000);
    public static final int DEFAULT_BULK_SIZE = 10_000;

    private final UUID jobId;
    private final int bulkSize;
    private final ScheduledExecutorService scheduler;
    private final Executor executor;
    private final CollectExpression<Row, ?> uidExpression;
    private final NodeJobsCounter nodeJobsCounter;
    private final Supplier<TReq> requestFactory;
    private final Function<String, TItem> itemFactory;
    private final BiConsumer<TReq, ActionListener<ShardResponse>> operation;
    private final String localNodeId;
    private final Collector<ShardResponse, TAcc, TResult> collector;
    private int numItems = -1;

    public ShardDMLExecutor(UUID jobId,
                            int bulkSize,
                            ScheduledExecutorService scheduler,
                            Executor executor,
                            CollectExpression<Row, ?> uidExpression,
                            ClusterService clusterService,
                            NodeJobsCounter nodeJobsCounter,
                            Supplier<TReq> requestFactory,
                            Function<String, TItem> itemFactory,
                            BiConsumer<TReq, ActionListener<ShardResponse>> transportAction,
                            Collector<ShardResponse, TAcc, TResult> collector
                            ) {
        this.jobId = jobId;
        this.bulkSize = bulkSize;
        this.scheduler = scheduler;
        this.executor = executor;
        this.uidExpression = uidExpression;
        this.nodeJobsCounter = nodeJobsCounter;
        this.requestFactory = requestFactory;
        this.itemFactory = itemFactory;
        this.operation = transportAction;
        this.localNodeId = getLocalNodeId(clusterService);
        this.collector = collector;
    }

    private void addRowToRequest(TReq req, Row row) {
        numItems++;
        uidExpression.setNextRow(row);
        req.add(numItems, itemFactory.apply((String) uidExpression.value()));
    }

    private CompletableFuture<TAcc> executeBatch(TReq request) {
        FutureActionListener<ShardResponse, TAcc> listener = new FutureActionListener<>((a) -> {
            TAcc acc = collector.supplier().get();
            collector.accumulator().accept(acc, a);
            return acc;
        });

        nodeJobsCounter.increment(localNodeId);
        CompletableFuture<TAcc> result = listener.whenComplete((r, f) -> nodeJobsCounter.decrement(localNodeId));
        operation.accept(request, withRetry(request, listener));
        return result;
    }

    private RetryListener<ShardResponse> withRetry(TReq request, FutureActionListener<ShardResponse, TAcc> listener) {
        return new RetryListener<>(
            scheduler,
            l -> operation.accept(request, l),
            listener,
            BACKOFF_POLICY
        );
    }

    @Override
    public CompletableFuture<TResult> apply(BatchIterator<Row> batchIterator) {
        BatchIterator<TReq> reqBatchIterator =
            BatchIterators.partition(batchIterator, bulkSize, requestFactory, this::addRowToRequest, r -> false);
        // If the source batch iterator does not involve IO, mostly in-memory structures are used which we want to free
        // as soon as possible. We do not want to throttle based on the targets node counter in such cases.
        Predicate<TReq> shouldPause = ignored -> true;
        if (batchIterator.hasLazyResultSet()) {
            shouldPause = ignored ->
                nodeJobsCounter.getInProgressJobsForNode(localNodeId) >= MAX_NODE_CONCURRENT_OPERATIONS;
        }

        return new BatchIteratorBackpressureExecutor<>(
            jobId,
            scheduler,
            executor,
            reqBatchIterator,
            this::executeBatch,
            collector.combiner(),
            collector.supplier().get(),
            shouldPause,
            BACKOFF_POLICY
        ).consumeIteratorAndExecute()
            .thenApply(collector.finisher());
    }

    @Nullable
    private static String getLocalNodeId(ClusterService clusterService) {
        String nodeId = null;
        try {
            nodeId = clusterService.localNode().getId();
        } catch (IllegalStateException e) {
            LOGGER.debug("Unable to get local node id", e);
        }
        return nodeId;
    }

    private static <A> A processResponse(ShardResponse shardResponse, Function<ShardResponse, A> f) {
        Exception failure = shardResponse.failure();
        if (failure != null) {
            Throwables.throwIfUnchecked(failure);
            throw new RuntimeException(failure);
        }
        return f.apply(shardResponse);
    }

    private static Long toRowCount(ShardResponse shardResponse) {
        return Long.valueOf(processResponse(shardResponse, ShardResponse::successRowCount));
    }

    private static List<Object[]> toResultRows(ShardResponse shardResponse) {
        List<Object[]> result = processResponse(shardResponse, ShardResponse::getResultRows);
        return result == null ? List.of() : result;
    }

    public static final Collector<ShardResponse, long[], Iterable<Row>> ROW_COUNT_COLLECTOR = Collector.of(
        () -> new long[]{0L},
        (acc, response) -> acc[0] += toRowCount(response),
        (acc, response) -> {
            acc[0] += response[0];
            return acc;
        },
        acc -> List.of(new Row1(acc[0]))
    );

    public static final Collector<ShardResponse, List<Object[]>, Iterable<Row>> RESULT_ROW_COLLECTOR = Collector.of(
        ArrayList::new,
        (acc, response) -> acc.addAll(toResultRows(response)),
        (acc, response) -> {
            acc.addAll(response);
            return acc;
        },
        CollectionBucket::new
    );
}
