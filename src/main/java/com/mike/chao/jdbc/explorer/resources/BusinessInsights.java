package com.mike.chao.jdbc.explorer.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

@Component
public class BusinessInsights {

    private static final String ANALYSIS_PLAYBOOK = """
        📈 Business Insight Decision Playbook 📈

        Responsibility split:
        - MCP tools execute deterministic work: inspect schemas, run SQL, aggregate metrics, segment data, compare periods,
          calculate rates, confidence intervals, test statistics, contribution deltas, and persist accepted findings.
        - The LLM performs analytical judgment: choose the right template, translate business questions into metric definitions,
          form hypotheses, interpret MCP outputs, identify caveats, prioritize recommendations, and explain trade-offs.

        Automatic analysis templates:
        1. Funnel analysis: define ordered steps, count users/entities per step, calculate step conversion and drop-off,
           segment by channel, product, region, cohort, device, or customer type, then identify the largest constrained step.
        2. Retention analysis: define activation event and return event, compute period-N retention, compare cohorts,
           and flag segments with statistically meaningful deterioration.
        3. Cohort analysis: group entities by acquisition, signup, first purchase, or launch exposure date, track behavior over
           equal-age periods, and separate lifecycle effects from calendar effects.
        4. User segmentation: cluster or rule-split customers by behavior, value, needs, acquisition channel, lifecycle stage,
           geography, or product usage, then size and profile each segment.
        5. RFM: score recency, frequency, and monetary value, identify champions, loyal customers, at-risk customers,
           hibernating customers, and reactivation opportunities.
        6. LTV: estimate customer lifetime value from margin, repeat rate, churn, discounting, and acquisition cost assumptions;
           report sensitivity to retention and gross margin.
        7. Repeat purchase analysis: measure repurchase rate, time to second purchase, purchase frequency, category migration,
           and segment-level differences.
        8. Channel attribution: compare first-touch, last-touch, multi-touch, and incremental views; separate volume growth from
           quality changes such as retention, refund rate, or gross margin.
        9. Product basket analysis: find products bought together, attachment rates, cross-sell opportunities, cannibalization,
           and bundles that improve margin or retention.
        10. Inventory turnover: compute turnover, days inventory outstanding, stockout indicators, slow-moving SKUs, and demand
            variability by product and location.
        11. Gross margin analysis: decompose revenue, discounting, COGS, mix, refund, freight, and channel fee effects;
            separate sales growth from profitability deterioration.
        12. Customer churn analysis: define churn threshold, calculate logo/customer/revenue churn, identify leading indicators,
            and prioritize intervention segments by recoverable value.

        Root cause analysis workflow:
        1. Confirm metric definitions, grain, currency, timezone, and included/excluded transactions.
        2. Compare against last period, same period last year, and expected run-rate or forecast when available.
        3. Decompose by region, channel, product, plan, customer segment, cohort, and large accounts.
        4. Quantify contribution to the total change and rank the largest negative and positive drivers.
        5. Inspect price, order volume, conversion rate, refunds, discounts, mix, churn, and data quality anomalies.
        6. Validate statistical significance or practical significance, and note sample-size limitations.
        7. Produce executable recommendations with owner, expected impact, confidence, and next measurement.

        Proactive insight scan:
        - Growth with conversion deterioration, single-customer concentration, abnormal refund rates, sales growth with margin
          erosion, low-quality acquisition channels, deteriorating retention, inventory risk, churn spikes, and anomalous mix shifts.

        Hypothesis validation workflow:
        1. Convert the user's hypothesis into measurable metrics and affected population.
        2. Define pre/post or treatment/control windows, excluding holidays, outages, migrations, and launch ramp periods when needed.
        3. Choose a suitable test such as proportion test, t-test, non-parametric test, chi-square test, or regression.
        4. Calculate confidence intervals, effect size, baseline, observed delta, and p-value when sample size permits.
        5. Check heterogeneous effects across key segments.
        6. State conclusion, limitations, alternative explanations, and recommended follow-up experiment or monitoring.
        """;

    private final List<String> insights;

    public BusinessInsights() {
        this.insights = Collections.synchronizedList(new ArrayList<>());
    }

    public void addInsight(String insight) {
        Optional.ofNullable(insight)
            .filter(i -> !i.isBlank())
            .ifPresent(insights::add);
    }

    public String getInsights() {
        if (insights.isEmpty()) {
            return "No insights yet";
        }
        var header = """
            📊 Business Intelligence Memo 📊
            Key insights discovered during data analysis:

            """;

        var text = IntStream.range(0, insights.size())
            .mapToObj(i -> (i + 1) + ". " + insights.get(i))
            .collect(Collectors.joining("\n"));
        return header  + text;
    }

    public String getAnalysisPlaybook() {
        return ANALYSIS_PLAYBOOK;
    }
}
