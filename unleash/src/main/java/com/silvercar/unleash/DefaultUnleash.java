package com.silvercar.unleash;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import com.silvercar.unleash.event.EventDispatcher;
import com.silvercar.unleash.event.ToggleEvaluated;
import com.silvercar.unleash.metric.UnleashMetricService;
import com.silvercar.unleash.metric.UnleashMetricServiceImpl;
import com.silvercar.unleash.repository.FeatureToggleRepository;
import com.silvercar.unleash.repository.HttpToggleFetcher;
import com.silvercar.unleash.repository.ToggleBackupHandlerFile;
import com.silvercar.unleash.repository.ToggleRepository;
import com.silvercar.unleash.strategy.*;
import com.silvercar.unleash.util.UnleashConfig;
import com.silvercar.unleash.variant.VariantUtil;

import static java.util.Optional.ofNullable;
import static com.silvercar.unleash.Variant.DISABLED_VARIANT;

public final class DefaultUnleash implements Unleash {
    private static final List<Strategy> BUILTIN_STRATEGIES = Arrays.asList(new DefaultStrategy(),
            new ApplicationHostnameStrategy(),
            new GradualRolloutRandomStrategy(),
            new GradualRolloutSessionIdStrategy(),
            new GradualRolloutUserIdStrategy(),
            new RemoteAddressStrategy(),
            new UserWithIdStrategy(),
            new FlexibleRolloutStrategy());

    private static final UnknownStrategy UNKNOWN_STRATEGY = new UnknownStrategy();

    private final UnleashMetricService metricService;
    private final ToggleRepository toggleRepository;
    private final Map<String, Strategy> strategyMap;
    private final UnleashContextProvider contextProvider;
    private final EventDispatcher eventDispatcher;
    private final UnleashConfig config;

    private static FeatureToggleRepository defaultToggleRepository(UnleashConfig unleashConfig) {
        return new FeatureToggleRepository(
                unleashConfig,
                new HttpToggleFetcher(unleashConfig),
                new ToggleBackupHandlerFile(unleashConfig)
        );
    }

    public DefaultUnleash(UnleashConfig unleashConfig, Strategy... strategies) {
        this(unleashConfig, defaultToggleRepository(unleashConfig), strategies);
    }

    public DefaultUnleash(UnleashConfig unleashConfig, ToggleRepository toggleRepository, Strategy... strategies) {
        this.config = unleashConfig;
        this.toggleRepository = toggleRepository;
        this.strategyMap = buildStrategyMap(strategies);
        this.contextProvider = unleashConfig.getContextProvider();
        this.eventDispatcher = new EventDispatcher(unleashConfig);
        this.metricService = new UnleashMetricServiceImpl(unleashConfig, unleashConfig.getScheduledExecutor());
        metricService.register(strategyMap.keySet());
    }

    @Override
    public boolean isEnabled(final String toggleName) {
        return isEnabled(toggleName, false);
    }

    @Override
    public boolean isEnabled(final String toggleName, final boolean defaultSetting) {
        return isEnabled(toggleName, contextProvider.getContext(), defaultSetting);
    }

    @Override
    public boolean isEnabled(final String toggleName, final UnleashContext context, final boolean defaultSetting) {
        return isEnabled(toggleName, context, (n, c) -> defaultSetting);
    }

    @Override
    public boolean isEnabled(final String toggleName, final BiFunction<String, UnleashContext, Boolean> fallbackAction) {
        return isEnabled(toggleName, contextProvider.getContext(), fallbackAction);
    }

    @Override
    public boolean isEnabled(String toggleName, UnleashContext context, BiFunction<String, UnleashContext, Boolean> fallbackAction) {
        boolean enabled = checkEnabled(toggleName, context, fallbackAction);
        count(toggleName, enabled);
        eventDispatcher.dispatch(new ToggleEvaluated(toggleName, enabled));
        return enabled;
    }

    private boolean checkEnabled(String toggleName, UnleashContext context, BiFunction<String, UnleashContext, Boolean> fallbackAction) {
        FeatureToggle featureToggle = toggleRepository.getToggle(toggleName);
        boolean enabled;
        UnleashContext enhancedContext = context.applyStaticFields(config);

        if (featureToggle == null) {
            enabled = fallbackAction.apply(toggleName, enhancedContext);
        } else if(!featureToggle.isEnabled()) {
            enabled = false;
        } else if(featureToggle.getStrategies().size() == 0) {
            return true;
        } else {
            enabled = featureToggle.getStrategies().stream()
                    .anyMatch(as -> getStrategy(as.getName()).isEnabled(as.getParameters(), enhancedContext, as.getConstraints()));
        }
        return enabled;
    }

    @Override
    public Variant getVariant(String toggleName, UnleashContext context) {
        return getVariant(toggleName, context, DISABLED_VARIANT);
    }

    @Override
    public Variant getVariant(String toggleName, UnleashContext context, Variant defaultValue) {
        FeatureToggle featureToggle = toggleRepository.getToggle(toggleName);
        boolean enabled = checkEnabled(toggleName, context, (n, c) -> false);
        Variant variant = enabled ? new VariantUtil().selectVariant(featureToggle, context, defaultValue) : defaultValue;
        metricService.countVariant(toggleName, variant.getName());
        return variant;
    }

    @Override
    public Variant getVariant(String toggleName) {
        return getVariant(toggleName, contextProvider.getContext());
    }

    @Override
    public Variant getVariant(String toggleName, Variant defaultValue) {
        return getVariant(toggleName, contextProvider.getContext(), defaultValue);
    }

    public Optional<FeatureToggle> getFeatureToggleDefinition(String toggleName) {
        return ofNullable(toggleRepository.getToggle(toggleName));
    }

    public List<String> getFeatureToggleNames() {
        return toggleRepository.getFeatureNames();
    }

    public void count(final String toggleName, boolean enabled) {
        metricService.count(toggleName, enabled);
    }

    private Map<String, Strategy> buildStrategyMap(Strategy[] strategies) {
        Map<String, Strategy> map = new HashMap<>();

        BUILTIN_STRATEGIES.forEach(strategy -> map.put(strategy.getName(), strategy));

        if (strategies != null) {
            for (Strategy strategy : strategies) {
                map.put(strategy.getName(), strategy);
            }
        }

        return map;
    }

    private Strategy getStrategy(String strategy) {
        return strategyMap.containsKey(strategy) ? strategyMap.get(strategy) : UNKNOWN_STRATEGY;
    }

    @Override
    public void shutdown() {
        config.getScheduledExecutor().shutdown();
    }
}
