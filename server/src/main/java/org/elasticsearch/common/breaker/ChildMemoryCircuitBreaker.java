/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.breaker;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.indices.breaker.BreakerSettings;
import org.elasticsearch.indices.breaker.CircuitBreakerService;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Breaker that will check a parent's when incrementing
 */
public class ChildMemoryCircuitBreaker implements CircuitBreaker {

    private final long memoryBytesLimit;
    private final BreakerSettings settings;
    private final AtomicLong used;
    private final AtomicLong trippedCount;
    private final Logger logger;
    private final CircuitBreakerService parent;
    private final String name;

    /**
     * Create a circuit breaker that will break if the number of estimated
     * bytes grows above the limit. All estimations will be multiplied by
     * the given overheadConstant. This breaker starts with 0 bytes used.
     * @param settings settings to configure this breaker
     * @param parent parent circuit breaker service to delegate tripped breakers to
     * @param name the name of the breaker
     */
    public ChildMemoryCircuitBreaker(BreakerSettings settings, Logger logger, CircuitBreakerService parent) {
        this(settings, null, logger, parent);
    }

    /**
     * Create a circuit breaker that will break if the number of estimated
     * bytes grows above the limit. All estimations will be multiplied by
     * the given overheadConstant. Uses the given oldBreaker to initialize
     * the starting offset.
     * @param settings settings to configure this breaker
     * @param parent parent circuit breaker service to delegate tripped breakers to
     * @param name the name of the breaker
     * @param oldBreaker the previous circuit breaker to inherit the used value from (starting offset)
     */
    public ChildMemoryCircuitBreaker(BreakerSettings settings,
                                     ChildMemoryCircuitBreaker oldBreaker,
                                     Logger logger,
                                     CircuitBreakerService parent) {
        this.name = settings.getName();
        this.settings = settings;
        this.memoryBytesLimit = settings.getLimit();
        if (oldBreaker == null) {
            this.used = new AtomicLong(0);
            this.trippedCount = new AtomicLong(0);
        } else {
            this.used = oldBreaker.used;
            this.trippedCount = oldBreaker.trippedCount;
        }
        this.logger = logger;
        if (logger.isTraceEnabled()) {
            logger.trace("creating ChildCircuitBreaker with settings {}", this.settings);
        }
        this.parent = parent;
    }

    /**
     * Method used to trip the breaker, delegates to the parent to determine
     * whether to trip the breaker or not
     */
    private void circuitBreak(String fieldName, long bytesNeeded) {
        this.trippedCount.incrementAndGet();
        final String message = "[" + this.name + "] Data too large, data for [" + fieldName + "]" +
                " would be [" + bytesNeeded + "/" + new ByteSizeValue(bytesNeeded) + "]" +
                ", which is larger than the limit of [" +
                memoryBytesLimit + "/" + new ByteSizeValue(memoryBytesLimit) + "]";
        logger.debug("{}", message);
        throw new CircuitBreakingException(message, bytesNeeded, memoryBytesLimit);
    }

    /**
     * Add a number of bytes, tripping the circuit breaker if the aggregated
     * estimates are above the limit. Automatically trips the breaker if the
     * memory limit is set to 0. Will never trip the breaker if the limit is
     * set &lt; 0, but can still be used to aggregate estimations.
     * @param bytes number of bytes to add to the breaker
     * @return number of "used" bytes so far
     */
    @Override
    public double addEstimateBytesAndMaybeBreak(long bytes, String label) throws CircuitBreakingException {
        // short-circuit on no data allowed, immediately throwing an exception
        if (memoryBytesLimit == 0) {
            circuitBreak(label, bytes);
        }

        long newUsed;
        // If there is no limit (-1), we can optimize a bit by using
        // .addAndGet() instead of looping (because we don't have to check a
        // limit), which makes the RamAccountingTermsEnum case faster.
        if (this.memoryBytesLimit == -1) {
            newUsed = noLimit(bytes, label);
        } else {
            newUsed = limit(bytes, label);
        }

        // Additionally, we need to check that we haven't exceeded the parent's limit
        try {
            parent.checkParentLimit(bytes, label);
        } catch (CircuitBreakingException e) {
            // If the parent breaker is tripped, this breaker has to be
            // adjusted back down because the allocation is "blocked" but the
            // breaker has already been incremented
            this.addWithoutBreaking(-bytes);
            throw e;
        }
        return newUsed;
    }

    @Override
    public long addBytesRangeAndMaybeBreak(long minAcceptableBytes, long wantedBytes, String label) throws CircuitBreakingException {
        if (minAcceptableBytes == wantedBytes) {
            addEstimateBytesAndMaybeBreak(wantedBytes, label);
            return wantedBytes;
        }
        // -1 is no limit
        if (memoryBytesLimit == -1) {
            used.addAndGet(wantedBytes);
            return wantedBytes;
        }
        if (memoryBytesLimit == 0) {
            circuitBreak(label, wantedBytes);
        }

        long actualIncrement = wantedBytes;
        long newUsed;
        long currentUsed;
        do {
            currentUsed = this.used.get();
            newUsed = currentUsed + actualIncrement;
            if (newUsed > memoryBytesLimit) {
                if ((currentUsed + minAcceptableBytes) > memoryBytesLimit) {
                    circuitBreak(label, newUsed);
                }
                actualIncrement = (long) memoryBytesLimit - currentUsed;
                currentUsed = -1L; // force compareAndSet to fail to have another iteration
            }
        } while (!this.used.compareAndSet(currentUsed, newUsed));

        // Additionally, we need to check that we haven't exceeded the parent's limit
        try {
            parent.checkParentLimit(actualIncrement, label);
        } catch (CircuitBreakingException e) {
            // If the parent breaker is tripped, this breaker has to be
            // adjusted back down because the allocation is "blocked" but the
            // breaker has already been incremented
            this.addWithoutBreaking(-actualIncrement);
            throw e;
        }

        return newUsed - currentUsed;
    }

    private long noLimit(long bytes, String label) {
        long newUsed;
        newUsed = this.used.addAndGet(bytes);
        if (logger.isTraceEnabled()) {
            logger.trace("[{}] Adding [{}][{}] to used bytes [new used: [{}], limit: [-1b]]",
                this.name, new ByteSizeValue(bytes), label, new ByteSizeValue(newUsed));
        }
        return newUsed;
    }

    private long limit(long bytes, String label) {
        long newUsed;// Otherwise, check the addition and commit the addition, looping if
        // there are conflicts. May result in additional logging, but it's
        // trace logging and shouldn't be counted on for additions.
        long currentUsed;
        do {
            currentUsed = this.used.get();
            newUsed = currentUsed + bytes;
            if (logger.isTraceEnabled()) {
                logger.trace(
                    "[{}] Adding [{}][{}] to used bytes [new used: [{}], limit: {} [{}]]",
                    this.name,
                    new ByteSizeValue(bytes),
                    label,
                    new ByteSizeValue(newUsed),
                    memoryBytesLimit,
                    new ByteSizeValue(memoryBytesLimit)
                );
            }
            if (memoryBytesLimit > 0 && newUsed > memoryBytesLimit) {
                logger.warn(
                    "[{}] New used memory {} [{}] for data of [{}] would be larger than configured breaker: {} [{}], breaking",
                    this.name,
                    newUsed,
                    new ByteSizeValue(newUsed),
                    label,
                    memoryBytesLimit,
                    new ByteSizeValue(memoryBytesLimit)
                );
                circuitBreak(label, newUsed);
            }
            // Attempt to set the new used value, but make sure it hasn't changed
            // underneath us, if it has, keep trying until we are able to set it
        } while (!this.used.compareAndSet(currentUsed, newUsed));
        return newUsed;
    }

    /**
     * Add an <b>exact</b> number of bytes, not checking for tripping the
     * circuit breaker. This bypasses the overheadConstant multiplication.
     *
     * Also does not check with the parent breaker to see if the parent limit
     * has been exceeded.
     *
     * @param bytes number of bytes to add to the breaker
     * @return number of "used" bytes so far
     */
    @Override
    public long addWithoutBreaking(long bytes) {
        long u = used.addAndGet(bytes);
        if (logger.isTraceEnabled()) {
            logger.trace("[{}] Adjusted breaker by [{}] bytes, now [{}]", this.name, bytes, u);
        }
        assert u >= 0 : "Used bytes: [" + u + "] must be >= 0";
        return u;
    }

    /**
     * @return the number of aggregated "used" bytes so far
     */
    @Override
    public long getUsed() {
        return this.used.get();
    }

    /**
     * @return the number of bytes that can be added before the breaker trips
     */
    @Override
    public long getLimit() {
        return this.memoryBytesLimit;
    }

    /**
     * @return the number of times the breaker has been tripped
     */
    @Override
    public long getTrippedCount() {
        return this.trippedCount.get();
    }

    /**
     * @return the name of the breaker
     */
    @Override
    public String getName() {
        return this.name;
    }
}
