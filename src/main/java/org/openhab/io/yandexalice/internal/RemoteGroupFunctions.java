package org.openhab.io.yandexalice.internal;

import java.util.List;
import java.util.Locale;

import org.openhab.core.items.GroupFunction;
import org.openhab.core.items.Item;
import org.openhab.core.library.types.ArithmeticGroupFunction;
import org.openhab.core.library.types.DateTimeGroupFunction;
import org.openhab.core.types.State;
import org.openhab.core.types.TypeParser;

/** Recreates the group aggregation declared by the remote Item. */
public final class RemoteGroupFunctions {
    private RemoteGroupFunctions() {
    }

    public static GroupFunction create(String name, List<String> params, Item base) {
        String function = name.toUpperCase(Locale.ROOT);
        return switch (function) {
            case "", "EQUALITY" -> new GroupFunction.Equality();
            case "OR" -> new ArithmeticGroupFunction.Or(param(params, 0, base), param(params, 1, base));
            case "AND" -> new ArithmeticGroupFunction.And(param(params, 0, base), param(params, 1, base));
            case "NOR" -> new ArithmeticGroupFunction.NOr(param(params, 0, base), param(params, 1, base));
            case "NAND" -> new ArithmeticGroupFunction.NAnd(param(params, 0, base), param(params, 1, base));
            case "XOR" -> new ArithmeticGroupFunction.Xor(param(params, 0, base), param(params, 1, base));
            case "COUNT" -> new ArithmeticGroupFunction.Count(param(params, 0, base));
            case "SUM" -> new ArithmeticGroupFunction.Sum();
            case "AVG" -> new ArithmeticGroupFunction.Avg();
            case "MIN" -> new ArithmeticGroupFunction.Min();
            case "MAX" -> new ArithmeticGroupFunction.Max();
            case "MEDIAN" -> new ArithmeticGroupFunction.Median();
            case "EARLIEST" -> new DateTimeGroupFunction.Earliest();
            case "LATEST" -> new DateTimeGroupFunction.Latest();
            default -> throw new IllegalArgumentException("Unsupported remote Group function: " + name);
        };
    }

    private static State param(List<String> params, int index, Item base) {
        if (params.size() <= index) {
            throw new IllegalArgumentException("Missing remote Group function parameter " + index);
        }
        State state = TypeParser.parseState(base.getAcceptedDataTypes(), params.get(index));
        if (state == null) {
            throw new IllegalArgumentException("Invalid remote Group function parameter: " + params.get(index));
        }
        return state;
    }
}
