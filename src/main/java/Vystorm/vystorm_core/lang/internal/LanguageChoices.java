package Vystorm.vystorm_core.lang.internal;

import Vystorm.vystorm_core.lang.CoreLanguage;
import Vystorm.vystorm_core.lang.Localization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure selection logic behind {@code /language}: which languages are offered, how an argument maps to a choice and
 * what tab completion suggests. Core-internal.
 */
public final class LanguageChoices {
    /** Words accepted for "follow the client language". */
    public static final Set<String> AUTO_WORDS = Set.of("auto", "automatic", "automatisch", "client", "自动", "自動",
            "automatique", "automático", "automatico", "авто", "автоматически");

    /** One selectable language. {@code coverage} in percent over all plugins. */
    public record Option(String code, String name, double coverage) {
        /** Coverage rounded down to a whole percent, for display (99.6 is not "100"). */
        public int percent() { return (int) Math.floor(coverage + 1e-9); }
    }

    private final Set<String> selectable;
    private final Function<String, String> names;
    private final Function<String, Double> coverage;
    private final Function<String, List<String>> chain;

    public LanguageChoices(Set<String> selectable, Function<String, String> names, Function<String, Double> coverage,
                           Function<String, List<String>> chain) {
        this.selectable = Set.copyOf(selectable);
        this.names = names;
        this.coverage = coverage;
        this.chain = chain;
    }

    /** From the running service. */
    public static LanguageChoices of(Localization service) {
        return new LanguageChoices(service.selectableLanguages(), CoreLanguage::nativeName, service::coverage, service::fallbackChain);
    }

    /** Offered languages: English first, then by code. */
    public List<Option> options() {
        List<Option> out = new ArrayList<>();
        for (String code : selectable) out.add(new Option(code, names.apply(code), coverage.apply(code)));
        out.sort((a, b) -> a.code().equals(Localization.FALLBACK) ? -1 : b.code().equals(Localization.FALLBACK) ? 1 : a.code().compareTo(b.code()));
        return out;
    }

    /**
     * The choice for a command argument: {@code auto} (also "automatic", "自动", …), an offered code ({@code zh_cn},
     * {@code zh-CN}), a code that falls back to an offered one ({@code de_at} → {@code de}, {@code zh} → {@code zh_cn}),
     * or an offered language's own name ({@code Deutsch}, {@code 简体中文}). Empty if nothing matches.
     */
    public Optional<String> parse(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String raw = input.trim();
        String code = Localization.normalize(raw);
        if (AUTO_WORDS.contains(code)) return Optional.of(Localization.AUTO);
        if (selectable.contains(code)) return Optional.of(code);
        if (code.matches("[a-z]{2,8}(_[a-z0-9]{1,8})*")) {
            boolean english = Localization.languagePart(code).equals(Localization.FALLBACK);
            for (String step : chain.apply(code)) {
                if (step.equals(Localization.FALLBACK) && !english) break;
                if (selectable.contains(step)) return Optional.of(step);
            }
        }
        for (Option option : options()) if (option.name().equalsIgnoreCase(raw)) return Optional.of(option.code());
        return Optional.empty();
    }

    /** Tab completion for a language argument: {@code auto} and the offered codes starting with {@code prefix}. */
    public List<String> complete(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (Localization.AUTO.startsWith(p)) out.add(Localization.AUTO);
        for (Option option : options()) if (option.code().startsWith(Localization.normalize(p))) out.add(option.code());
        return out;
    }

    /** Whether an argument is meant as a language (so {@code /language de} never looks up a player named "de"). */
    public boolean isLanguage(String input) { return parse(input).isPresent(); }
}
