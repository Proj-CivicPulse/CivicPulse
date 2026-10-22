package com.civicpulse.backend_spring.service.incident;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds life-safety hazards in report text: the problems that can kill
 * someone (docs/priority-calibration-proxy.md). Fixed phrase patterns, so
 * every flag can quote the words that raised it.
 *
 * <p>Deliberately narrow. A missed hazard costs what it costs today (the
 * officer reads the reports); a false one costs an officer a click to clear,
 * and after that the detector never overrides them. Known gaps: negation
 * ("no exposed wires") and code-mixed text are not handled.
 */
public final class LifeSafetyDetector {

    private LifeSafetyDetector() {
    }

    /** A hazard found: what kind, and the words that matched. */
    public record Finding(String hazard, String phrase) {

        /** The flag's reason, as an officer reads it. At most 255 characters. */
        public String reason() {
            String text = "Reports mention " + hazard + ": “" + phrase + "”";
            return text.length() <= 255 ? text : text.substring(0, 254) + "…";
        }
    }

    private record Hazard(String name, Pattern pattern) {
    }

    private static Pattern words(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final String COVER = "(?:cover|lid|slab|grill|grate)";

    /** First hazard in this order wins, so the reason names one problem. */
    private static final List<Hazard> HAZARDS = List.of(
            new Hazard("an open drain or manhole", words(
                    "\\b(?:open|uncovered|unclosed)\\s+(?:storm\\s+)?(?:drain|manhole|sewer|nala|nallah)s?\\b"
                            + "|\\b(?:drain|manhole|sewer)\\s+" + COVER + "s?\\s+(?:is\\s+|are\\s+|has\\s+been\\s+)?"
                            + "(?:missing|broken|gone|removed|stolen|caved\\s+in|open)\\b"
                            + "|\\b(?:missing|broken|no)\\s+(?:drain\\s+|manhole\\s+|sewer\\s+)" + COVER + "s?\\b"
                            + "|\\bmanhole\\s+(?:is\\s+|has\\s+been\\s+|left\\s+)?(?:open|uncovered)\\b")),
            new Hazard("exposed electrical wiring", words(
                    "\\b(?:exposed|live|naked|bare|hanging|dangling|loose|snapped|fallen|broken)\\s+"
                            + "(?:electric(?:al|ity)?\\s+|power\\s+|current\\s+)?(?:wire|wires|wiring|cable|cables)\\b"
                            + "|\\belectric(?:al)?\\s+shock\\b|\\bgot\\s+(?:a\\s+)?shock\\b"
                            + "|\\b(?:sparks?|sparking)\\b"
                            + "|\\bcurrent\\s+(?:is\\s+)?(?:leaking|passing|coming)\\b")),
            new Hazard("sewage overflowing", words(
                    "\\bsewage\\s+(?:is\\s+)?(?:overflow\\w*|backing\\s+up|flooding|flowing\\s+on|on\\s+the\\s+road|entering)"
                            + "|\\boverflowing\\s+(?:sewage|sewer|manhole|ugd)\\b|\\bsewer\\s+overflow\\w*")),
            new Hazard("a collapsed road", words(
                    "\\b(?:road|surface|footpath|pavement|culvert|bridge)\\s+(?:has\\s+|is\\s+|had\\s+)?"
                            + "(?:collapsed|caved\\s+in|caving\\s+in|sunk|sinking|given\\s+way)\\b"
                            + "|\\bsink\\s?hole\\b|\\bcave-?in\\b")),
            new Hazard("a fallen tree or pole", words(
                    "\\b(?:tree|branch|pole|electric\\s+pole|lamp\\s+post|hoarding|wall)\\s+(?:has\\s+|is\\s+)?"
                            + "(?:fallen|fell|collapsed|about\\s+to\\s+fall|leaning\\s+dangerously|uprooted)\\b"
                            + "|\\b(?:fallen|uprooted)\\s+(?:tree|branch|pole)\\b"))
    );

    /** The first hazard found in any of the texts, in hazard order. */
    public static Optional<Finding> detect(List<String> texts) {
        for (Hazard hazard : HAZARDS) {
            for (String text : texts) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                Matcher m = hazard.pattern().matcher(text);
                if (m.find()) {
                    return Optional.of(new Finding(hazard.name(),
                            m.group().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)));
                }
            }
        }
        return Optional.empty();
    }
}
