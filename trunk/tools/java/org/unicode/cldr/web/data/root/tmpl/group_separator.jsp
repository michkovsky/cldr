<%@ include file="report_top.jspf" %>

<p> Enter the character used as the grouping separator.</p>

<%
//  Copy "x=___"  from input to output URL
subCtx.setQuery(SurveyMain.QUERY_SECTION,subCtx.field(SurveyMain.QUERY_SECTION));

SurveyForum.printSectionTableOpenShort(subCtx, "//ldml/numbers/symbols/group");

SurveyForum.showXpathShort(subCtx, "//ldml/numbers/symbols/group");

SurveyForum.printSectionTableCloseShort(subCtx, "//ldml/numbers/symbols/group");

%>
