package ptatoolkit.util;

import java.util.Comparator;

import ptatoolkit.pta.util.Numberable;

public class NumberableComparators {

    public final static Comparator<Pair<? extends Numberable, ? extends Numberable>> pairComparator = (a, b) -> {
        int i = a.getFirst().getID();
        int j = b.getFirst().getID();
        if (i != j) {
            return i - j;
        }
        i = a.getSecond().getID();
        j = b.getSecond().getID();
        return i - j;
    };

    public final static Comparator<Triple<? extends Numberable, ? extends Numberable, ? extends Numberable>> tripleComparator = (a, b) -> {
        int i = a.getFirst().getID();
        int j = b.getFirst().getID();
        if (i != j) {
            return i - j;
        }
        i = a.getSecond().getID();
        j = b.getSecond().getID();
        if (i != j) {
            return i - j;
        }
        i = a.getThird().getID();
        j = b.getThird().getID();
        return i - j;
    };

}