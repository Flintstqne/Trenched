package org.flintstqne.entrenched.BlueMapHook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class RegionNameGenerator {

    private static final String[] PREFIXES = {
            "Ash","Oak","Elm","Pine","Birch","Stone","Flint","Thorn","Briar","Mist","Frost","Wind",
            "Mill","Market","Cross","Bridge","Hall","Court","Farm","Port","Watch",
            "Brook","River","Silver","Grey","Salt","Brine","Still","Deep","Swift",
            "High","Iron","Red","Black","White","Storm","Wolf","Bear","Hawk"
    };

    private static final String[] SUFFIXES = {
            "shire","vale","pine","ford","worth","ville","field","hurst","stone",
            "ton","chapel","dale","mouth","beck","ness","wich","ridge","ster"
    };

    private static final String[] MODIFIERS = {
            "Olde ","New ","Great ","Fort "
    };

    public static String generateRegionName() {
        return generateRegionName(new Random());
    }

    public static String generateRegionName(Random random) {
        String prefix = PREFIXES[random.nextInt(PREFIXES.length)];
        String suffix = SUFFIXES[random.nextInt(SUFFIXES.length)];

        // Vowel collision adjustment
        if ("aeiou".indexOf(Character.toLowerCase(prefix.charAt(prefix.length() - 1))) != -1
                && "aeiou".indexOf(Character.toLowerCase(suffix.charAt(0))) != -1) {
            suffix = suffix.substring(1);
        }

        // Optional modifier (20% chance)
        if (random.nextDouble() < 0.2) {
            prefix = MODIFIERS[random.nextInt(MODIFIERS.length)] + prefix;
        }

        return prefix + suffix;
    }

    public static List<String> generateUniqueNames(int count, Random random) {
        Set<String> set = new HashSet<>();
        int guard = 0;

        while (set.size() < count) {
            set.add(generateRegionName(random));
            guard++;
            if (guard > count * 200) break; // safety guard
        }

        return new ArrayList<>(set);
    }
}
