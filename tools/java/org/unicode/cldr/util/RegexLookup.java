package org.unicode.cldr.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.unicode.cldr.draft.FileUtilities;
import org.unicode.cldr.util.RegexLookup.Finder;

import com.ibm.icu.impl.Row;
import com.ibm.icu.impl.Row.R2;
import com.ibm.icu.text.Transform;

/**
 * Lookup items according to a set of regex patterns. Returns the value according to the first pattern that matches. Not thread-safe.
 * @param <T>
 */
public class RegexLookup<T> implements Iterable<Row.R2<Finder, T>>{
    private static final boolean DEBUG = false;
    private final Map<Finder, Row.R2<Finder,T>> entries = new LinkedHashMap<Finder, Row.R2<Finder,T>>();
    private Transform<String, ? extends Finder> patternTransform = RegexFinderTransform;
    private Transform<String, ? extends T> valueTransform;
    private Merger<T> valueMerger;
    private final boolean allowNull = false;
    
    public abstract static class Finder {
        abstract public String[] getInfo();
        abstract public boolean find(String item, Object context);
        public int getFailPoint(String source) { return -1; }
        // must also define toString
    }
    
    public static class RegexFinder extends Finder {
        protected final Matcher matcher;
        public RegexFinder(String pattern) {
            matcher = Pattern.compile(pattern, Pattern.COMMENTS).matcher("");
        }
        public boolean find(String item, Object context) {
            return matcher.reset(item).find();
        }
        @Override
        public String[] getInfo() {
            int limit = matcher.groupCount() + 1;
            String[] value = new String[limit];
            for (int i = 0; i < limit; ++i) {
                value[i] = matcher.group(i);
            }
            return value;
        }
        public String toString() {
            return matcher.pattern().pattern();
        }
        @Override
        public boolean equals(Object obj) {
            return toString().equals(obj.toString());
        }
        @Override
        public int hashCode() {
            return toString().hashCode();
        }
    }
    
    public static Transform<String, RegexFinder> RegexFinderTransform = new Transform<String, RegexFinder>() {
        public RegexFinder transform(String source) {
            return new RegexFinder(source);
        }
    };

    /**
     * Allows for merging items of the same type.
     * @param <T>
     */
    public interface Merger<T> {
        T merge(T a, T into);
    }

    /**
     * Returns the result of a regex lookup.
     * @param source
     * @return
     */
    public final T get(String source) {
        return get(source, null, null);
    }

    /**
     * Returns the result of a regex lookup, with the group arguments that matched.
     * @param source
     * @param context TODO
     * @return
     */
    public T get(String source, Object context, CldrUtility.Output<String[]> arguments) {
        while (true) {
            for (R2<Finder, T> entry : entries.values()) {
                Finder matcher = entry.get0();
                if (matcher.find(source, context)) {
                    if (arguments != null) {
                        arguments.value = matcher.getInfo();
                    }
                    return entry.get1();
                } else if (DEBUG) {
                    int failPoint = matcher.getFailPoint(source);
                    String show = source.substring(0,failPoint) + "$" + source.substring(failPoint);
                    show += "";
                }
            }
            break;
        }
        return null;
    }

    public int getFailPoint(Matcher matcher, String source) {
        for (int i = 1; i < source.length(); ++i) {
            matcher.reset(source.substring(0,i)).find();
            boolean hitEnd = matcher.hitEnd();
            if (!hitEnd) {
                return i - 1;
            }
        }
        return 0;
    }

    /**
     * Create a RegexLookup. It will take a list of key/value pairs, where the key is a regex pattern and the value is what gets returned.
     * @param patternTransform Used to transform string patterns into a Pattern. Can be used to process replacements (like variables).
     * @param valueTransform Used to transform string values into another form.
     * @param valueMerger Used to merge values with the same key.
     */
    public static <T,U> RegexLookup<T> of(Transform<String, Finder> patternTransform, Transform<String, T> valueTransform, Merger<T> valueMerger) {
        return new RegexLookup<T>().setValueTransform(valueTransform).setPatternTransform(patternTransform).setValueMerger(valueMerger);
    }
    
    public static <T> RegexLookup<T> of(Transform<String,T> valueTransform) {
        return new RegexLookup<T>().setValueTransform(valueTransform).setPatternTransform(RegexFinderTransform);
    }

    public static <T> RegexLookup<T> of() {
        return new RegexLookup<T>().setPatternTransform(RegexFinderTransform);
    }

    public RegexLookup<T> setValueTransform(Transform<String, ? extends T> valueTransform) {
        this.valueTransform = valueTransform;
        return this;
    }

    public RegexLookup<T> setPatternTransform(Transform<String, ? extends Finder> patternTransform) {
        this.patternTransform = patternTransform;
        return this;
    }

    public RegexLookup<T> setValueMerger(Merger<T> valueMerger) {
        this.valueMerger = valueMerger;
        return this;
    }

    /**
     * Load a RegexLookup from a file. Opens a file relative to the class, and adds lines separated by "; ". Lines starting with # are comments.
     */
    public RegexLookup<T> loadFromFile(Class<?> baseClass, String filename) {
        try {
            BufferedReader file = FileUtilities.openFile(baseClass, filename);
            for (int lineNumber = 0;; ++lineNumber) {
                String line = file.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                int pos = line.indexOf("; ");
                if (pos < 0) {
                    throw new IllegalArgumentException("Failed to read RegexLookup File " + filename + "\t\t(" + lineNumber + ") " + line);
                }
                String source = line.substring(0,pos).trim();
                String target = line.substring(pos+2).trim();
                add(source, target);
            }
            return this;
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Add a pattern/value pair, transforming the target according to the constructor valueTransform (if not null).
     * @param stringPattern
     * @param target
     * @return this, for chaining
     */
    public RegexLookup<T> add(String stringPattern, String target) {
        try {
            @SuppressWarnings("unchecked")
            T result = valueTransform == null ? (T) target : valueTransform.transform(target);
            return add(stringPattern, result);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to add <" + stringPattern + "> => <" + target + ">", e);
        }
    }

    /**
     * Add a pattern/value pair.
     * @param stringPattern
     * @param target
     * @return this, for chaining
     */
    public RegexLookup<T> add(String stringPattern, T target) {
        Finder pattern0 = patternTransform.transform(stringPattern);
        return add(pattern0, target);
    }

    /**
     * Add a pattern/value pair.
     * @param pattern
     * @param target
     * @return this, for chaining
     */
    public RegexLookup<T> add(Finder pattern, T target) {
        if (!allowNull && target == null) {
            throw new NullPointerException("null disallowed, unless allowNull(true) is called.");
        }
        R2<Finder, T> old = entries.get(pattern);
        if (old == null) {
            entries.put(pattern, Row.of(pattern, target));
        } else if (valueMerger != null) {
            valueMerger.merge(target, old.get1());
        } else {
            throw new IllegalArgumentException("Duplicate matcher without Merger defined " + pattern + "; old: " + old + "; new: " + target);
        }
        return this;
    }

    @Override
    public Iterator<R2<Finder, T>> iterator() {
        return Collections.unmodifiableCollection(entries.values()).iterator();
    }
}