package org.unicode.cldr.unittest;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.unicode.cldr.draft.EnumLookup;
import org.unicode.cldr.draft.ScriptCategories;
import org.unicode.cldr.draft.ScriptCategories.Groupings;
import org.unicode.cldr.draft.ScriptMetadata;
import org.unicode.cldr.draft.ScriptMetadata.IdUsage;
import org.unicode.cldr.draft.ScriptMetadata.Info;
import org.unicode.cldr.draft.ScriptMetadata.Shaping;
import org.unicode.cldr.draft.ScriptMetadata.Trinary;
import org.unicode.cldr.unittest.TestAll.TestInfo;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CLDRPaths;
import org.unicode.cldr.util.Containment;
import org.unicode.cldr.util.Factory;
import org.unicode.cldr.util.StandardCodes;
import org.unicode.cldr.util.With;
import org.unicode.cldr.util.XPathParts;

import com.ibm.icu.dev.test.TestFmwk;
import com.ibm.icu.dev.util.CollectionUtilities;
import com.ibm.icu.dev.util.Relation;
import com.ibm.icu.impl.Row;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.lang.UScript;
import com.ibm.icu.text.Transform;
import com.ibm.icu.text.UnicodeSet;

public class TestScriptMetadata extends TestFmwkPlus {
    public static void main(String[] args) {
        new TestScriptMetadata().run(args);
    }

    public void TestLookup() {
        EnumLookup<IdUsage> temp = EnumLookup.of(IdUsage.class);
        assertEquals("", IdUsage.LIMITED_USE, temp.forString("limited Use"));
    }

    public void TestScriptOfSample() {
        BitSet bitset = new BitSet();
        for (String script : ScriptMetadata.getScripts()) {
            Info info0 = ScriptMetadata.getInfo(script);
            assertEquals("Sample must be single character", 1,
                info0.sampleChar.codePointCount(0, info0.sampleChar.length()));
            int scriptCode = UScript.getScriptExtensions(info0.sampleChar.codePointAt(0), bitset);
            assertTrue("Must have single, valid script " + scriptCode, scriptCode >= 0);
        }
    }

    public void TestBasic() {
        Info info0 = ScriptMetadata.getInfo(UScript.LATIN);
        if (ScriptMetadata.errors.size() != 0) {
            errln("ScriptMetadata initialization errors\t" + ScriptMetadata.errors.size() + "\t"
                + CollectionUtilities.join(ScriptMetadata.errors, "\n"));
        }

        // Latin Latn 2 L European Recommended no no no no
        assertEquals("Latin-rank", 2, info0.rank);
        assertEquals("Latin-country", "IT", info0.originCountry);
        assertEquals("Latin-sample", "L", info0.sampleChar);
        assertEquals("Latin-id usage", ScriptMetadata.IdUsage.RECOMMENDED, info0.idUsage);
        assertEquals("Latin-ime?", Trinary.NO, info0.ime);
        assertEquals("Latin-lb letters?", Trinary.NO, info0.lbLetters);
        assertEquals("Latin-rtl?", Trinary.NO, info0.rtl);
        assertEquals("Latin-shaping", Shaping.MIN, info0.shapingReq);
        assertEquals("Latin-density", 1, info0.density);
        assertEquals("Latin-Case", Trinary.YES, info0.hasCase);

        info0 = ScriptMetadata.getInfo(UScript.HEBREW);
        assertEquals("Arabic-rtl", Trinary.YES, info0.rtl);
        assertEquals("Arabic-shaping", Shaping.NO, info0.shapingReq);
        assertEquals("Arabic-Case", Trinary.NO, info0.hasCase);
    }

    public void TestScripts() {
        UnicodeSet temp = new UnicodeSet();
        Set<String> missingScripts = new TreeSet<String>();
        Relation<IdUsage, String> map = Relation.of(new EnumMap<IdUsage, Set<String>>(IdUsage.class),
            LinkedHashSet.class);
        for (int i = UScript.COMMON; i < UScript.CODE_LIMIT; ++i) {
            Info info = ScriptMetadata.getInfo(i);
            if (info != null) {
                map.put(info.idUsage, UScript.getName(i) + "\t(" + UScript.getShortName(i) + ")\t" + info);
            } else {
                // There are many script codes that are not "real"; there are no Unicode characters for them.
                // separate those out.
                temp.applyIntPropertyValue(UProperty.SCRIPT, i);
                if (temp.size() != 0) { // is real
                    errln("Missing data for " + UScript.getName(i) + "\t(" + UScript.getShortName(i));
                } else { // is not real
                    missingScripts.add(UScript.getShortName(i));
                }
            }
        }
        for (Entry<IdUsage, String> entry : map.keyValueSet()) {
            logln(entry.getValue());
        }
        if (!missingScripts.isEmpty() && !logKnownIssue("6647", "missing script metadata: " + missingScripts.toString())) {
            errln("Also missing: " + missingScripts.toString());
        }
    }

    // lifted from ShowLanguages
    private static Set<String> getEnglishTypes(String type, int code, StandardCodes sc, CLDRFile english) {
        Set<String> result = new HashSet<String>(sc.getSurveyToolDisplayCodes(type));
        XPathParts parts = new XPathParts();
        for (Iterator<String> it = english.getAvailableIterator(code); it.hasNext();) {
            parts.set(it.next()); 
            String newType = parts.getAttributeValue(-1, "type");
            if (!result.contains(newType)) {
                result.add(newType);
            }
        }
        return result;
    }

    // lifted from ShowLanguages
    private static Set<String> getScriptsToShow(StandardCodes sc, CLDRFile english) {
        return getEnglishTypes("script", CLDRFile.SCRIPT_NAME, sc, english);
    }

    public void TestShowLanguages() {
        // lifted from ShowLanguages - this is what ShowLanguages tried to do.
        StandardCodes sc = StandardCodes.make();
        Factory cldrFactory = Factory.make(CLDRPaths.MAIN_DIRECTORY, ".*");
        CLDRFile english = cldrFactory.make("en", true);
        Set<String> bads = new TreeSet<String>();

        for (String s : getScriptsToShow(sc, english)) {
            if (ScriptMetadata.getInfo(s) == null) {
                bads.add(s);
            }
        }
        if (!bads.isEmpty() && !logKnownIssue("6647", "missing script metadata: " + bads.toString())) {
            errln("No metadata for scripts: " + bads.toString());
        }
    }

    public void TestGeographicGrouping() {
        CLDRFile english = TestInfo.getInstance().getEnglish();
        Set<Row.R3<IdUsage, String, String>> lines = new TreeSet<Row.R3<IdUsage, String, String>>();
        Set<String> extras = ScriptMetadata.getExtras();
        for (Entry<String, Info> sc : ScriptMetadata.iterable()) {
            String scriptCode = sc.getKey();
            if (extras.contains(scriptCode)) {
                continue;
            }
            Info info = sc.getValue();
            String continent = Containment.getContinent(info.originCountry);
            String container = !continent.equals("142") ? continent :
                Containment.getSubcontinent(info.originCountry);

            lines.add(
                Row.of(info.idUsage,
                    english.getName(CLDRFile.TERRITORY_NAME, continent),
                    info.idUsage
                    + "\t" + 
                    english.getName(CLDRFile.TERRITORY_NAME, container)
                    + "\t" + scriptCode + "\t" +
                    english.getName(CLDRFile.SCRIPT_NAME, scriptCode) 
                    ));
        }
        for (Row.R3<IdUsage, String, String> s : lines) {
            logln(s.get2());
        }
    }

    public void TestScriptCategories() {

        // test completeness
        Set<String> scripts = new TreeSet<String>(ScriptMetadata.getScripts());
        scripts.removeAll(Arrays.asList("Zinh", "Zyyy", "Zzzz"));
        logln("All: " + scripts);
        for (Groupings x : ScriptCategories.Groupings.values()) {
            logln(x + ": " + x.scripts.toString());
            scripts.removeAll(x.scripts);
        }
        assertEquals("Completeness", Collections.EMPTY_SET, scripts);

        // test no overlap
        assertEquals("Overlap", Collections.EMPTY_SET, scripts);
        for (Groupings x : ScriptCategories.Groupings.values()) {
            for (Groupings y : ScriptCategories.Groupings.values()) {
                if (y == x) continue;
                assertTrue("overlap", Collections.disjoint(x.scripts, y.scripts));
            }
        }

        assertEqualsX(Groupings.EUROPEAN, ScriptCategories.OLD_EUROPEAN);
        assertEqualsX(Groupings.MIDDLE_EASTERN, ScriptCategories.OLD_MIDDLE_EASTERN);
        assertEqualsX(Groupings.SOUTH_ASIAN, ScriptCategories.OLD_SOUTH_ASIAN);
        assertEqualsX(Groupings.SOUTHEAST_ASIAN, ScriptCategories.OLD_SOUTHEAST_ASIAN);
        assertEqualsX(Groupings.EAST_ASIAN, ScriptCategories.OLD_EAST_ASIAN);
        assertEqualsX(Groupings.AFRICAN, ScriptCategories.OLD_AFRICAN);
        assertEqualsX(Groupings.AMERICAN, ScriptCategories.OLD_AMERICAN);

        assertEqualsX("Historic: ", ScriptCategories.HISTORIC_SCRIPTS, ScriptCategories.OLD_HISTORIC_SCRIPTS);

    }

    private void assertEqualsX(Groupings aRaw, Set<String> bRaw) {
        assertEqualsX(aRaw.toString(), aRaw.scripts, bRaw);
    }

    public void assertEqualsX(String title, Set<String> a, Set<String> bRaw) {
        TreeSet b = With.in(bRaw).toCollection(ScriptCategories.TO_SHORT_SCRIPT, new TreeSet());

        Set<String> a_b = new TreeSet<String>(a);
        a_b.removeAll(b);
        Set<String> b_a = new TreeSet<String>(b);
        b_a.removeAll(a);
        assertEquals(title + " New vs Old, ", a_b.toString(), b_a.toString());
    }

}
