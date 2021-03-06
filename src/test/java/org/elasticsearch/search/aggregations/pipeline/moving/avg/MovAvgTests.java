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

package org.elasticsearch.search.aggregations.pipeline.moving.avg;


import com.google.common.collect.EvictingQueue;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilter;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram.Bucket;
import org.elasticsearch.search.aggregations.metrics.ValuesSourceMetricsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.avg.Avg;
import org.elasticsearch.search.aggregations.pipeline.BucketHelpers;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregationHelperTests;
import org.elasticsearch.search.aggregations.pipeline.SimpleValue;
import org.elasticsearch.search.aggregations.pipeline.movavg.models.*;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.*;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.search.aggregations.AggregationBuilders.avg;
import static org.elasticsearch.search.aggregations.AggregationBuilders.filter;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.max;
import static org.elasticsearch.search.aggregations.AggregationBuilders.min;
import static org.elasticsearch.search.aggregations.AggregationBuilders.range;
import static org.elasticsearch.search.aggregations.pipeline.PipelineAggregatorBuilders.movingAvg;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;

@ElasticsearchIntegrationTest.SuiteScopeTest
public class MovAvgTests extends ElasticsearchIntegrationTest {

    private static final String INTERVAL_FIELD = "l_value";
    private static final String VALUE_FIELD = "v_value";
    private static final String GAP_FIELD = "g_value";

    static int interval;
    static int numBuckets;
    static int windowSize;
    static double alpha;
    static double beta;
    static double gamma;
    static int period;
    static HoltWintersModel.SeasonalityType seasonalityType;
    static BucketHelpers.GapPolicy gapPolicy;
    static ValuesSourceMetricsAggregationBuilder metric;
    static List<PipelineAggregationHelperTests.MockBucket> mockHisto;

    static Map<String, ArrayList<Double>> testValues;


    enum MovAvgType {
        SIMPLE ("simple"), LINEAR("linear"), EWMA("ewma"), HOLT("holt"), HOLT_WINTERS("holt_winters");

        private final String name;

        MovAvgType(String s) {
            name = s;
        }

        public String toString(){
            return name;
        }
    }

    enum MetricTarget {
        VALUE ("value"), COUNT("count");

        private final String name;

        MetricTarget(String s) {
            name = s;
        }

        public String toString(){
            return name;
        }
    }


    @Override
    public void setupSuiteScopeCluster() throws Exception {
        createIndex("idx");
        createIndex("idx_unmapped");
        List<IndexRequestBuilder> builders = new ArrayList<>();


        interval = 5;
        numBuckets = randomIntBetween(6, 80);
        period = randomIntBetween(1, 5);
        windowSize = randomIntBetween(period * 2, 10);  // start must be 2*period to play nice with HW
        alpha = randomDouble();
        beta = randomDouble();
        gamma = randomDouble();
        seasonalityType = randomBoolean() ? HoltWintersModel.SeasonalityType.ADDITIVE : HoltWintersModel.SeasonalityType.MULTIPLICATIVE;


        gapPolicy = randomBoolean() ? BucketHelpers.GapPolicy.SKIP : BucketHelpers.GapPolicy.INSERT_ZEROS;
        metric = randomMetric("the_metric", VALUE_FIELD);
        mockHisto = PipelineAggregationHelperTests.generateHistogram(interval, numBuckets, randomDouble(), randomDouble());

        testValues = new HashMap<>(8);

        for (MovAvgType type : MovAvgType.values()) {
            for (MetricTarget target : MetricTarget.values()) {
                setupExpected(type, target);
            }
        }

        for (PipelineAggregationHelperTests.MockBucket mockBucket : mockHisto) {
            for (double value : mockBucket.docValues) {
                builders.add(client().prepareIndex("idx", "type").setSource(jsonBuilder().startObject()
                        .field(INTERVAL_FIELD, mockBucket.key)
                        .field(VALUE_FIELD, value).endObject()));
            }
        }

        // Used for specially crafted gap tests
        builders.add(client().prepareIndex("idx", "gap_type").setSource(jsonBuilder().startObject()
                .field(INTERVAL_FIELD, 0)
                .field(GAP_FIELD, 1).endObject()));

        builders.add(client().prepareIndex("idx", "gap_type").setSource(jsonBuilder().startObject()
                .field(INTERVAL_FIELD, 49)
                .field(GAP_FIELD, 1).endObject()));

        for (int i = -10; i < 10; i++) {
            builders.add(client().prepareIndex("neg_idx", "type").setSource(
                    jsonBuilder().startObject().field(INTERVAL_FIELD, i).field(VALUE_FIELD, 10).endObject()));
        }

        indexRandom(true, builders);
        ensureSearchable();
    }

    /**
     * Calculates the moving averages for a specific (model, target) tuple based on the previously generated mock histogram.
     * Computed values are stored in the testValues map.
     *
     * @param type      The moving average model to use
     * @param target    The document field "target", e.g. _count or a field value
     */
    private void setupExpected(MovAvgType type, MetricTarget target) {
        ArrayList<Double> values = new ArrayList<>(numBuckets);
        EvictingQueue<Double> window = EvictingQueue.create(windowSize);

        for (PipelineAggregationHelperTests.MockBucket mockBucket : mockHisto) {
            double metricValue;
            double[] docValues = mockBucket.docValues;

            // Gaps only apply to metric values, not doc _counts
            if (mockBucket.count == 0 && target.equals(MetricTarget.VALUE)) {
                // If there was a gap in doc counts and we are ignoring, just skip this bucket
                if (gapPolicy.equals(BucketHelpers.GapPolicy.SKIP)) {
                    values.add(null);
                    continue;
                } else if (gapPolicy.equals(BucketHelpers.GapPolicy.INSERT_ZEROS)) {
                    // otherwise insert a zero instead of the true value
                    metricValue = 0.0;
                } else {
                    metricValue = PipelineAggregationHelperTests.calculateMetric(docValues, metric);
                }

            } else {
                // If this isn't a gap, or is a _count, just insert the value
                metricValue = target.equals(MetricTarget.VALUE) ? PipelineAggregationHelperTests.calculateMetric(docValues, metric) : mockBucket.count;
            }

            window.offer(metricValue);
            switch (type) {
                case SIMPLE:
                    values.add(simple(window));
                    break;
                case LINEAR:
                    values.add(linear(window));
                    break;
                case EWMA:
                    values.add(ewma(window));
                    break;
                case HOLT:
                    values.add(holt(window));
                    break;
                case HOLT_WINTERS:
                    // HW needs at least 2 periods of data to start
                    if (window.size() >= period * 2) {
                        values.add(holtWinters(window));
                    } else {
                        values.add(null);
                    }

                    break;
            }

        }
        testValues.put(type.toString() + "_" + target.toString(), values);
    }

    /**
     * Simple, unweighted moving average
     *
     * @param window Window of values to compute movavg for
     * @return
     */
    private double simple(Collection<Double> window) {
        double movAvg = 0;
        for (double value : window) {
            movAvg += value;
        }
        movAvg /= window.size();
        return movAvg;
    }

    /**
     * Linearly weighted moving avg
     *
     * @param window Window of values to compute movavg for
     * @return
     */
    private double linear(Collection<Double> window) {
        double avg = 0;
        long totalWeight = 1;
        long current = 1;

        for (double value : window) {
            avg += value * current;
            totalWeight += current;
            current += 1;
        }
        return avg / totalWeight;
    }

    /**
     * Exponentionally weighted (EWMA, Single exponential) moving avg
     *
     * @param window Window of values to compute movavg for
     * @return
     */
    private double ewma(Collection<Double> window) {
        double avg = 0;
        boolean first = true;

        for (double value : window) {
            if (first) {
                avg = value;
                first = false;
            } else {
                avg = (value * alpha) + (avg * (1 - alpha));
            }
        }
        return avg;
    }

    /**
     * Holt-Linear (Double exponential) moving avg
     * @param window Window of values to compute movavg for
     * @return
     */
    private double holt(Collection<Double> window) {
        double s = 0;
        double last_s = 0;

        // Trend value
        double b = 0;
        double last_b = 0;

        int counter = 0;

        double last;
        for (double value : window) {
            last = value;
            if (counter == 1) {
                s = value;
                b = value - last;
            } else {
                s = alpha * value + (1.0d - alpha) * (last_s + last_b);
                b = beta * (s - last_s) + (1 - beta) * last_b;
            }

            counter += 1;
            last_s = s;
            last_b = b;
        }

        return s + (0 * b) ;
    }

    /**
     * Holt winters (triple exponential) moving avg
     * @param window Window of values to compute movavg for
     * @return
     */
    private double holtWinters(Collection<Double> window) {
        // Smoothed value
        double s = 0;
        double last_s = 0;

        // Trend value
        double b = 0;
        double last_b = 0;

        // Seasonal value
        double[] seasonal = new double[window.size()];

        double padding = seasonalityType.equals(HoltWintersModel.SeasonalityType.MULTIPLICATIVE) ? 0.0000000001 : 0;

        int counter = 0;
        double[] vs = new double[window.size()];
        for (double v : window) {
            vs[counter] = v + padding;
            counter += 1;
        }


        // Initial level value is average of first season
        // Calculate the slopes between first and second season for each period
        for (int i = 0; i < period; i++) {
            s += vs[i];
            b += (vs[i] - vs[i + period]) / 2;
        }
        s /= (double) period;
        b /= (double) period;
        last_s = s;
        last_b = b;

        // Calculate first seasonal
        if (Double.compare(s, 0.0) == 0 || Double.compare(s, -0.0) == 0) {
            Arrays.fill(seasonal, 0.0);
        } else {
            for (int i = 0; i < period; i++) {
                seasonal[i] = vs[i] / s;
            }
        }

        for (int i = period; i < vs.length; i++) {
            if (seasonalityType.equals(HoltWintersModel.SeasonalityType.MULTIPLICATIVE)) {
                s = alpha * (vs[i] / seasonal[i - period]) + (1.0d - alpha) * (last_s + last_b);
            } else {
                s = alpha * (vs[i] - seasonal[i - period]) + (1.0d - alpha) * (last_s + last_b);
            }

            b = beta * (s - last_s) + (1 - beta) * last_b;

            if (seasonalityType.equals(HoltWintersModel.SeasonalityType.MULTIPLICATIVE)) {
                seasonal[i] = gamma * (vs[i] / (last_s + last_b )) + (1 - gamma) * seasonal[i - period];
            } else {
                seasonal[i] = gamma * (vs[i] - (last_s + last_b )) + (1 - gamma) * seasonal[i - period];
            }

            last_s = s;
            last_b = b;
        }

        int seasonCounter = (window.size() - 1) - period;
        if (seasonalityType.equals(HoltWintersModel.SeasonalityType.MULTIPLICATIVE)) {
            return s + (0 * b) * seasonal[seasonCounter % window.size()];
        } else {
            return s + (0 * b) + seasonal[seasonCounter % window.size()];
        }
    }


    /**
     * test simple moving average on single value field
     */
    @Test
    public void simpleSingleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.SIMPLE.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.SIMPLE.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<PipelineAggregationHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            PipelineAggregationHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    public void linearSingleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new LinearModel.LinearModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new LinearModel.LinearModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.LINEAR.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.LINEAR.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<PipelineAggregationHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            PipelineAggregationHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    public void ewmaSingleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new EwmaModel.EWMAModelBuilder().alpha(alpha))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new EwmaModel.EWMAModelBuilder().alpha(alpha))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.EWMA.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.EWMA.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<PipelineAggregationHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            PipelineAggregationHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    public void holtSingleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new HoltLinearModel.HoltLinearModelBuilder().alpha(alpha).beta(beta))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new HoltLinearModel.HoltLinearModelBuilder().alpha(alpha).beta(beta))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.HOLT.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.HOLT.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<PipelineAggregationHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            PipelineAggregationHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    public void HoltWintersValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new HoltWintersModel.HoltWintersModelBuilder()
                                                .alpha(alpha).beta(beta).gamma(gamma).period(period).seasonalityType(seasonalityType))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new HoltWintersModel.HoltWintersModelBuilder()
                                                .alpha(alpha).beta(beta).gamma(gamma).period(period).seasonalityType(seasonalityType))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.HOLT_WINTERS.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.HOLT_WINTERS.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<PipelineAggregationHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            PipelineAggregationHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    public void testPredictNegativeKeysAtStart() {

        SearchResponse response = client()
                .prepareSearch("neg_idx")
                .setTypes("type")
                .addAggregation(
                        histogram("histo")
                                .field(INTERVAL_FIELD)
                                .interval(1)
                                .subAggregation(avg("avg").field(VALUE_FIELD))
                                .subAggregation(
                                        movingAvg("movavg_values").window(windowSize).modelBuilder(new SimpleModel.SimpleModelBuilder())
                                                .gapPolicy(gapPolicy).predict(5).setBucketsPaths("avg"))).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(25));

        for (int i = 0; i < 20; i++) {
            Bucket bucket = buckets.get(i);
            assertThat(bucket, notNullValue());
            assertThat((long) bucket.getKey(), equalTo((long) i - 10));
            assertThat(bucket.getDocCount(), equalTo(1l));
            Avg avgAgg = bucket.getAggregations().get("avg");
            assertThat(avgAgg, notNullValue());
            assertThat(avgAgg.value(), equalTo(10d));
            SimpleValue movAvgAgg = bucket.getAggregations().get("movavg_values");
            assertThat(movAvgAgg, notNullValue());
            assertThat(movAvgAgg.value(), equalTo(10d));
        }

        for (int i = 20; i < 25; i++) {
            System.out.println(i);
            Bucket bucket = buckets.get(i);
            assertThat(bucket, notNullValue());
            assertThat((long) bucket.getKey(), equalTo((long) i - 10));
            assertThat(bucket.getDocCount(), equalTo(0l));
            Avg avgAgg = bucket.getAggregations().get("avg");
            assertThat(avgAgg, nullValue());
            SimpleValue movAvgAgg = bucket.getAggregations().get("movavg_values");
            assertThat(movAvgAgg, notNullValue());
            assertThat(movAvgAgg.value(), equalTo(10d));
        }
    }


    @Test
    public void testSizeZeroWindow() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(0)
                                            .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept a window that is zero");

        } catch (SearchPhaseExecutionException exception) {
           // All good
        }
    }

    @Test
    public void testBadParent() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            range("histo").field(INTERVAL_FIELD).addRange(0, 10)
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(0)
                                            .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept non-histogram as parent");

        } catch (SearchPhaseExecutionException exception) {
            // All good
        }
    }

    @Test
    public void testNegativeWindow() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(-10)
                                            .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("_count"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept a window that is negative");

        } catch (SearchPhaseExecutionException exception) {
            //Throwable rootCause = exception.unwrapCause();
            //assertThat(rootCause, instanceOf(SearchParseException.class));
            //assertThat("[window] value must be a positive, non-zero integer.  Value supplied was [0] in [movingAvg].", equalTo(exception.getMessage()));
        }
    }

    @Test
    public void testNoBucketsInHistogram() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field("test").interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat(buckets.size(), equalTo(0));
    }

    @Test
    public void testNoBucketsInHistogramWithPredict() {
        int numPredictions = randomIntBetween(1,10);
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field("test").interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric")
                                        .predict(numPredictions))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat(buckets.size(), equalTo(0));
    }

    @Test
    public void testZeroPrediction() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(windowSize)
                                            .modelBuilder(randomModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .predict(0)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept a prediction size that is zero");

        } catch (SearchPhaseExecutionException exception) {
            // All Good
        }
    }

    @Test
    public void testNegativePrediction() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(windowSize)
                                            .modelBuilder(randomModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .predict(-10)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept a prediction size that is negative");

        } catch (SearchPhaseExecutionException exception) {
            // All Good
        }
    }

    /**
     * This test uses the "gap" dataset, which is simply a doc at the beginning and end of
     * the INTERVAL_FIELD range.  These docs have a value of 1 in GAP_FIELD.
     * This test verifies that large gaps don't break things, and that the mov avg roughly works
     * in the correct manner (checks direction of change, but not actual values)
     */
    @Test
    public void testGiantGap() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                .subAggregation(min("the_metric").field(GAP_FIELD))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(randomModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50));

        double lastValue = ((SimpleValue)(buckets.get(0).getAggregations().get("movavg_values"))).value();
        assertThat(Double.compare(lastValue, 0.0d), greaterThanOrEqualTo(0));

        double currentValue;
        for (int i = 1; i < 49; i++) {
            SimpleValue current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                // Since there are only two values in this test, at the beginning and end, the moving average should
                // decrease every step (until it reaches zero).  Crude way to check that it's doing the right thing
                // without actually verifying the computed values.  Should work for all types of moving avgs and
                // gap policies
                assertThat(Double.compare(lastValue, currentValue), greaterThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }


        SimpleValue current = buckets.get(49).getAggregations().get("movavg_values");
        assertThat(current, notNullValue());
        currentValue = current.value();

        if (gapPolicy.equals(BucketHelpers.GapPolicy.SKIP)) {
            // if we are ignoring, movavg could go up (holt) or stay the same (simple, linear, ewma)
            assertThat(Double.compare(lastValue, currentValue), lessThanOrEqualTo(0));
        } else if (gapPolicy.equals(BucketHelpers.GapPolicy.INSERT_ZEROS)) {
            // If we insert zeros, this should always increase the moving avg since the last bucket has a real value
            assertThat(Double.compare(lastValue, currentValue), equalTo(-1));
        }
    }

    /**
     * Big gap, but with prediction at the end.
     */
    @Test
    public void testGiantGapWithPredict() {
        int numPredictions = randomIntBetween(1, 10);

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                .subAggregation(min("the_metric").field(GAP_FIELD))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(randomModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric")
                                        .predict(numPredictions))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50 + numPredictions));

        double lastValue = ((SimpleValue)(buckets.get(0).getAggregations().get("movavg_values"))).value();
        assertThat(Double.compare(lastValue, 0.0d), greaterThanOrEqualTo(0));

        double currentValue;
        for (int i = 1; i < 49; i++) {
            SimpleValue current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                // Since there are only two values in this test, at the beginning and end, the moving average should
                // decrease every step (until it reaches zero).  Crude way to check that it's doing the right thing
                // without actually verifying the computed values.  Should work for all types of moving avgs and
                // gap policies
                assertThat(Double.compare(lastValue, currentValue), greaterThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }

        SimpleValue current = buckets.get(49).getAggregations().get("movavg_values");
        assertThat(current, notNullValue());
        currentValue = current.value();

        if (gapPolicy.equals(BucketHelpers.GapPolicy.SKIP)) {
            // if we are ignoring, movavg could go up (holt) or stay the same (simple, linear, ewma)
            assertThat(Double.compare(lastValue, currentValue), lessThanOrEqualTo(0));
        } else if (gapPolicy.equals(BucketHelpers.GapPolicy.INSERT_ZEROS)) {
            // If we insert zeros, this should always increase the moving avg since the last bucket has a real value
            assertThat(Double.compare(lastValue, currentValue), equalTo(-1));
        }

        // Now check predictions
        for (int i = 50; i < 50 + numPredictions; i++) {
            // Unclear at this point which direction the predictions will go, just verify they are
            // not null, and that we don't have the_metric anymore
            assertThat((buckets.get(i).getAggregations().get("movavg_values")), notNullValue());
            assertThat((buckets.get(i).getAggregations().get("the_metric")), nullValue());
        }
    }

    /**
     * This test filters the "gap" data so that the first doc is excluded.  This leaves a long stretch of empty
     * buckets until the final bucket.  The moving avg should be zero up until the last bucket, and should work
     * regardless of mov avg type or gap policy.
     */
    @Test
    public void testLeftGap() {
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        filter("filtered").filter(new RangeQueryBuilder(INTERVAL_FIELD).from(1)).subAggregation(
                                histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                        .subAggregation(randomMetric("the_metric", GAP_FIELD))
                                        .subAggregation(movingAvg("movavg_values")
                                                .window(windowSize)
                                                .modelBuilder(randomModelBuilder())
                                                .gapPolicy(gapPolicy)
                                                .setBucketsPaths("the_metric"))
                        ))
                .execute().actionGet();

        assertSearchResponse(response);

        InternalFilter filtered = response.getAggregations().get("filtered");
        assertThat(filtered, notNullValue());
        assertThat(filtered.getName(), equalTo("filtered"));

        InternalHistogram<Bucket> histo = filtered.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50));

        double lastValue = 0;

        double currentValue;
        for (int i = 0; i < 50; i++) {
            SimpleValue current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                assertThat(Double.compare(lastValue, currentValue), lessThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }
    }

    @Test
    public void testLeftGapWithPredict() {
        int numPredictions = randomIntBetween(1, 10);
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        filter("filtered").filter(new RangeQueryBuilder(INTERVAL_FIELD).from(1)).subAggregation(
                                histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                        .subAggregation(randomMetric("the_metric", GAP_FIELD))
                                        .subAggregation(movingAvg("movavg_values")
                                                .window(windowSize)
                                                .modelBuilder(randomModelBuilder())
                                                .gapPolicy(gapPolicy)
                                                .setBucketsPaths("the_metric")
                                                .predict(numPredictions))
                        ))
                .execute().actionGet();

        assertSearchResponse(response);

        InternalFilter filtered = response.getAggregations().get("filtered");
        assertThat(filtered, notNullValue());
        assertThat(filtered.getName(), equalTo("filtered"));

        InternalHistogram<Bucket> histo = filtered.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50 + numPredictions));

        double lastValue = 0;

        double currentValue;
        for (int i = 0; i < 50; i++) {
            SimpleValue current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                assertThat(Double.compare(lastValue, currentValue), lessThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }

        // Now check predictions
        for (int i = 50; i < 50 + numPredictions; i++) {
            // Unclear at this point which direction the predictions will go, just verify they are
            // not null, and that we don't have the_metric anymore
            assertThat((buckets.get(i).getAggregations().get("movavg_values")), notNullValue());
            assertThat((buckets.get(i).getAggregations().get("the_metric")), nullValue());
        }
    }

    /**
     * This test filters the "gap" data so that the last doc is excluded.  This leaves a long stretch of empty
     * buckets after the first bucket.  The moving avg should be one at the beginning, then zero for the rest
     * regardless of mov avg type or gap policy.
     */
    @Test
    public void testRightGap() {
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        filter("filtered").filter(new RangeQueryBuilder(INTERVAL_FIELD).to(1)).subAggregation(
                                histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                        .subAggregation(randomMetric("the_metric", GAP_FIELD))
                                        .subAggregation(movingAvg("movavg_values")
                                                .window(windowSize)
                                                .modelBuilder(randomModelBuilder())
                                                .gapPolicy(gapPolicy)
                                                .setBucketsPaths("the_metric"))
                        ))
                .execute().actionGet();

        assertSearchResponse(response);

        InternalFilter filtered = response.getAggregations().get("filtered");
        assertThat(filtered, notNullValue());
        assertThat(filtered.getName(), equalTo("filtered"));

        InternalHistogram<Bucket> histo = filtered.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50));


        SimpleValue current = buckets.get(0).getAggregations().get("movavg_values");
        assertThat(current, notNullValue());

        double lastValue = current.value();

        double currentValue;
        for (int i = 1; i < 50; i++) {
            current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                assertThat(Double.compare(lastValue, currentValue), greaterThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }
    }

    @Test
    public void testRightGapWithPredict() {
        int numPredictions = randomIntBetween(1, 10);
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        filter("filtered").filter(new RangeQueryBuilder(INTERVAL_FIELD).to(1)).subAggregation(
                                histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                        .subAggregation(randomMetric("the_metric", GAP_FIELD))
                                        .subAggregation(movingAvg("movavg_values")
                                                .window(windowSize)
                                                .modelBuilder(randomModelBuilder())
                                                .gapPolicy(gapPolicy)
                                                .setBucketsPaths("the_metric")
                                                .predict(numPredictions))
                        ))
                .execute().actionGet();

        assertSearchResponse(response);

        InternalFilter filtered = response.getAggregations().get("filtered");
        assertThat(filtered, notNullValue());
        assertThat(filtered.getName(), equalTo("filtered"));

        InternalHistogram<Bucket> histo = filtered.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50 + numPredictions));


        SimpleValue current = buckets.get(0).getAggregations().get("movavg_values");
        assertThat(current, notNullValue());

        double lastValue = current.value();

        double currentValue;
        for (int i = 1; i < 50; i++) {
            current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                assertThat(Double.compare(lastValue, currentValue), greaterThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }

        // Now check predictions
        for (int i = 50; i < 50 + numPredictions; i++) {
            // Unclear at this point which direction the predictions will go, just verify they are
            // not null, and that we don't have the_metric anymore
            assertThat((buckets.get(i).getAggregations().get("movavg_values")), notNullValue());
            assertThat((buckets.get(i).getAggregations().get("the_metric")), nullValue());
        }
    }

    @Test
    public void testHoltWintersNotEnoughData() {
        try {
            SearchResponse response = client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(metric)
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(10)
                                            .modelBuilder(new HoltWintersModel.HoltWintersModelBuilder()
                                                    .alpha(alpha).beta(beta).gamma(gamma).period(20).seasonalityType(seasonalityType))
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("_count"))
                                    .subAggregation(movingAvg("movavg_values")
                                            .window(windowSize)
                                            .modelBuilder(new HoltWintersModel.HoltWintersModelBuilder()
                                                    .alpha(alpha).beta(beta).gamma(gamma).period(20).seasonalityType(seasonalityType))
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
        } catch (SearchPhaseExecutionException e) {
            // All good
        }

    }

    @Test
    public void testBadModelParams() {
        try {
            SearchResponse response = client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(metric)
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(10)
                                            .modelBuilder(randomModelBuilder(100))
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("_count"))
                    ).execute().actionGet();
        } catch (SearchPhaseExecutionException e) {
            // All good
        }

    }


    private void assertValidIterators(Iterator expectedBucketIter, Iterator expectedCountsIter, Iterator expectedValuesIter) {
        if (!expectedBucketIter.hasNext()) {
            fail("`expectedBucketIter` iterator ended before `actual` iterator, size mismatch");
        }
        if (!expectedCountsIter.hasNext()) {
            fail("`expectedCountsIter` iterator ended before `actual` iterator, size mismatch");
        }
        if (!expectedValuesIter.hasNext()) {
            fail("`expectedValuesIter` iterator ended before `actual` iterator, size mismatch");
        }
    }

    private void assertBucketContents(Histogram.Bucket actual, Double expectedCount, Double expectedValue) {
        // This is a gap bucket
        SimpleValue countMovAvg = actual.getAggregations().get("movavg_counts");
        if (expectedCount == null) {
            assertThat("[_count] movavg is not null", countMovAvg, nullValue());
        } else if (Double.isNaN(expectedCount)) {
            assertThat("[_count] movavg should be NaN, but is ["+countMovAvg.value()+"] instead", countMovAvg.value(), equalTo(Double.NaN));
        } else {
            assertThat("[_count] movavg is null", countMovAvg, notNullValue());
            assertThat("[_count] movavg does not match expected ["+countMovAvg.value()+" vs "+expectedCount+"]",
                    countMovAvg.value(), closeTo(expectedCount, 0.1));
        }

        // This is a gap bucket
        SimpleValue valuesMovAvg = actual.getAggregations().get("movavg_values");
        if (expectedValue == null) {
            assertThat("[value] movavg is not null", valuesMovAvg, Matchers.nullValue());
        } else if (Double.isNaN(expectedValue)) {
            assertThat("[value] movavg should be NaN, but is ["+valuesMovAvg.value()+"] instead", valuesMovAvg.value(), equalTo(Double.NaN));
        } else {
            assertThat("[value] movavg is null", valuesMovAvg, notNullValue());
            assertThat("[value] movavg does not match expected ["+valuesMovAvg.value()+" vs "+expectedValue+"]",
                    valuesMovAvg.value(), closeTo(expectedValue, 0.1));
        }
    }

    private MovAvgModelBuilder randomModelBuilder() {
        return randomModelBuilder(0);
    }

    private MovAvgModelBuilder randomModelBuilder(double padding) {
        int rand = randomIntBetween(0,3);

        // HoltWinters is excluded from random generation, because it's "cold start" behavior makes
        // randomized testing too tricky.  Should probably add dedicated, randomized tests just for HoltWinters,
        // which can compensate for the idiosyncrasies
        switch (rand) {
            case 0:
                return new SimpleModel.SimpleModelBuilder();
            case 1:
                return new LinearModel.LinearModelBuilder();
            case 2:
                return new EwmaModel.EWMAModelBuilder().alpha(alpha + padding);
            case 3:
                return new HoltLinearModel.HoltLinearModelBuilder().alpha(alpha + padding).beta(beta + padding);
            default:
                return new SimpleModel.SimpleModelBuilder();
        }
    }
    
    private ValuesSourceMetricsAggregationBuilder randomMetric(String name, String field) {
        int rand = randomIntBetween(0,3);

        switch (rand) {
            case 0:
                return min(name).field(field);
            case 2:
                return max(name).field(field);
            case 3:
                return avg(name).field(field);
            default:
                return avg(name).field(field);
        }    
    }

}
