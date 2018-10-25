package org.unicode.cldr.tool;

import org.unicode.cldr.util.DateTimeFormats;
import org.unicode.cldr.util.VerifyCompactNumbers;
import org.unicode.cldr.util.VerifyZones;

public class GenerateAllCharts {
    public static void main(String[] args) throws Exception {
        ShowLanguages.main(args);
        new ChartAnnotations().writeChart(null);
        new ChartSubdivisionNames().writeChart(null);
        GenerateBcp47Text.main(args);
        GenerateSidewaysView.main(args);
        ShowData.main(args);
        //GenerateTransformCharts.main(args);
        ShowKeyboards.main(args);
        ChartDelta.main(args);
        ChartCollation.main(args);
        VerifyCompactNumbers.main(args);
        VerifyZones.main(args);
        DateTimeFormats.main(args);
    }
}
