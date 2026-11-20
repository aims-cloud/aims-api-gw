package com.innogrid.aims.billing.service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.innogrid.aims.billing.entity.PricingPolicy;
import com.innogrid.aims.billing.repository.PricingPolicyRepository;
import com.innogrid.aims.metering.entity.MeteringHourly;
import com.innogrid.aims.metering.repository.MeteringHourlyRepository;

@Service
public class BillingService {

    private final MeteringHourlyRepository meteringRepository;
    private final PricingPolicyRepository pricingRepository;

    public BillingService(MeteringHourlyRepository meteringRepository,
                          PricingPolicyRepository pricingRepository) {
        this.meteringRepository = meteringRepository;
        this.pricingRepository = pricingRepository;
    }

    public List<Map<String, Object>> calculateCharges(String mdcId,
                                                       OffsetDateTime from, OffsetDateTime to) {
        List<MeteringHourly> metering = mdcId != null
                ? meteringRepository.findByMdcIdAndTimeBetweenOrderByTimeDesc(mdcId, from, to)
                : meteringRepository.findByTimeBetweenOrderByTimeDesc(from, to);

        List<PricingPolicy> policies = pricingRepository.findByActiveTrueOrderByMetricName();
        Map<String, Double> priceMap = policies.stream()
                .collect(Collectors.toMap(PricingPolicy::getMetricName, PricingPolicy::getUnitPrice));

        // MDC + 메트릭별 집계
        Map<String, Map<String, Double>> usage = new LinkedHashMap<>();
        for (MeteringHourly m : metering) {
            String key = m.getMdcId();
            usage.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .merge(m.getMetricName(), m.getAvgValue(), Double::sum);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : usage.entrySet()) {
            List<Map<String, Object>> details = new ArrayList<>();
            double total = 0;
            for (Map.Entry<String, Double> metric : entry.getValue().entrySet()) {
                double price = priceMap.getOrDefault(metric.getKey(), 0.0);
                double amount = metric.getValue() * price;
                total += amount;
                details.add(Map.of(
                        "metricName", metric.getKey(),
                        "usage", Math.round(metric.getValue() * 100.0) / 100.0,
                        "unitPrice", price,
                        "amount", Math.round(amount)
                ));
            }
            result.add(Map.of(
                    "mdcId", entry.getKey(),
                    "totalAmount", Math.round(total),
                    "details", details
            ));
        }
        return result;
    }

    public List<Map<String, Object>> getUsageCostTrend(OffsetDateTime from, OffsetDateTime to) {
        List<MeteringHourly> metering = meteringRepository.findByTimeBetweenOrderByTimeDesc(from, to);
        List<PricingPolicy> policies = pricingRepository.findByActiveTrueOrderByMetricName();
        Map<String, Double> priceMap = policies.stream()
                .collect(Collectors.toMap(PricingPolicy::getMetricName, PricingPolicy::getUnitPrice));

        // 날짜별 비용 집계
        Map<String, Double> dailyCost = new TreeMap<>();
        for (MeteringHourly m : metering) {
            String day = m.getTime().toLocalDate().toString();
            double price = priceMap.getOrDefault(m.getMetricName(), 0.0);
            dailyCost.merge(day, m.getAvgValue() * price, Double::sum);
        }

        return dailyCost.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey(), "cost", Math.round(e.getValue())))
                .toList();
    }
}
