package forge.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed form of a validity string such as {@code Creature.YouCtrl+attacking}.
 *
 * <p>Splitting those strings showed up as one of the hottest operations of the engine: the AI
 * checks the same handful of restrictions against every card again and again, and the game
 * scripts only ever use a small, fixed set of them. Since parsing is a pure function of the
 * string, the results are shared globally.</p>
 */
public final class ValidRestriction {
    /** Safety net in case a script ever generates restrictions dynamically. */
    private static final int MAX_CACHE_SIZE = 20000;

    private static final String[] NO_PROPERTIES = new String[0];

    private static final Map<String, ValidRestriction> CACHE = new ConcurrentHashMap<>();

    private final boolean negated;
    private final String type;
    private final String[] properties;

    public static ValidRestriction parse(final String restriction) {
        ValidRestriction result = CACHE.get(restriction);
        if (result != null) {
            return result;
        }
        result = new ValidRestriction(restriction);
        if (CACHE.size() < MAX_CACHE_SIZE) {
            CACHE.put(restriction, result);
        }
        return result;
    }

    private ValidRestriction(final String restriction) {
        // only the first dot separates the type from the exclusive restrictions
        final int dot = restriction.indexOf('.');
        final String inclusive = dot < 0 ? restriction : restriction.substring(0, dot);
        negated = inclusive.startsWith("!");
        type = negated ? inclusive.substring(1) : inclusive;
        properties = dot < 0 ? NO_PROPERTIES : restriction.substring(dot + 1).split("\\+");
    }

    /** Whether the inclusive restriction was prefixed with {@code !}. */
    public boolean isNegated() {
        return negated;
    }

    /** The inclusive restriction (a card type, {@code Player}, ...), without a negation sign. */
    public String getType() {
        return type;
    }

    /** The exclusive restrictions, never {@code null}. Must not be modified. */
    public String[] getProperties() {
        return properties;
    }
}
