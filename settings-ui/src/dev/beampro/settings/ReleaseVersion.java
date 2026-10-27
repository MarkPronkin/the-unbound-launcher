package dev.beampro.settings;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic release ordering, including the app's display spelling "0.1.0 alpha". */
final class ReleaseVersion implements Comparable<ReleaseVersion> {
    private static final Pattern FORMAT = Pattern.compile(
            "^[vV]?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:[-\\s]+([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");
    private final String[] core, pre;

    private ReleaseVersion(Matcher match) {
        core = new String[]{match.group(1), match.group(2), match.group(3)};
        pre = match.group(4) == null ? new String[0] : match.group(4).split("\\.", -1);
    }

    static ReleaseVersion parse(String value) {
        if (value == null) return null;
        Matcher match = FORMAT.matcher(value.trim());
        if (!match.matches()) return null;
        ReleaseVersion version = new ReleaseVersion(match);
        for (String part : version.pre) if (part.isEmpty()) return null;
        return version;
    }

    boolean prerelease() { return pre.length != 0; }

    private static int number(String left, String right) {
        left = left.replaceFirst("^0+(?!$)", "");
        right = right.replaceFirst("^0+(?!$)", "");
        return left.length() == right.length() ? left.compareTo(right) : Integer.compare(left.length(), right.length());
    }

    @Override public int compareTo(ReleaseVersion other) {
        for (int i = 0; i < core.length; i++) {
            int result = number(core[i], other.core[i]);
            if (result != 0) return result;
        }
        if (pre.length == 0 || other.pre.length == 0) return Integer.compare(other.pre.length, pre.length);
        for (int i = 0; i < Math.min(pre.length, other.pre.length); i++) {
            String left = pre[i], right = other.pre[i];
            boolean ln = left.matches("[0-9]+"), rn = right.matches("[0-9]+");
            int result = ln && rn ? number(left, right) : ln != rn ? (ln ? -1 : 1) : left.compareTo(right);
            if (result != 0) return result;
        }
        return Integer.compare(pre.length, other.pre.length);
    }
}
