define({
	root: ({
		copyright: "(C) 2012-2014 IBM Corporation and Others. All Rights Reserved",
		loading: "loading",
		loading2: "loading.",
		loading3: "loading..",

		loadingMsg_desc: "Current loading status",
		loading_reloading: "Force Reloading Page",
		loading_reload: "Reload",
		loading_retrying: "Retrying",
		loading_nocontent: "This locale cannot be displayed.",
		loadingOneRow: "loading....",
		voting: "Voting",
		checking: "Checking",

		itemCount: "Items: ${itemCount}",
		itemCountHidden: "Items shown: ${itemCount}; Items hidden at ${coverage} coverage level: ${skippedDueToCoverage}",
		itemCountAllHidden: "No items visible due to coverage level.",
		itemCountNone: "No items!",
		noVotingInfo: " (no voting info received)",
		newDataWaiting: "(new data waiting)",

		clickToCopy: "click to copy to input box",
		file_a_ticket: "Read all above links, and then click here to file a ticket only if changes are necessary. Do not submit a translation of this text without reading the above text and links.",
		file_ticket_unofficial: "This is not an official Survey Tool instance.",
		file_ticket_must: "You must file a ticket to modify this item.",
		file_ticket_notice: "May not be modified- see details.",
		
		htmlst: "Errors",
		htmldraft: "A",
		htmlvoted: "Voting",
		htmlcode: "Code",
		htmlbaseline: "$BASELINE_LANGUAGE_NAME",
		htmlproposed: "Winning",
		htmlothers: "Others",
		htmltoadd: "Add",
		htmlchange: "Change",
		htmlnoopinion: "Abstain",

		possibleProblems: "Possible problems with this locale:",

		flyoverst: "Status Icon",
		flyoverdraft: "Approval Status",
		flyovervoted: "Shows a checkmark if you voted",
		flyovercode: "Code for this item",
		extraAttribute_desc: "Additional specifiers for this item",
		extraAttribute_heading: "Note: there are additional specifiers for this item. Read the help page for further details.",
		flyoverbaseline: "Comparison value",
		flyoverproposed: "Winning value",
		flyoverothers: "Other non-winning items",
		flyoverchange: "Enter new values here",
		flyovernoopinion: "Abstain from voting on this item",
		"i-override_desc": "You have voted on this item with a lower vote count (shown in parenthesis).",

		itemInfoBlank: "This area shows further details about the selected item.",

		draftStatus: "Status: ${0}",
		confirmed: "Confirmed", 
		approved: "Approved", 
		unconfirmed: "Unconfirmed", 
		contributed: "Contributed", 
		provisional: "Provisional",
		missing: "Missing",


                adminDeadThreadsHeader: "Deadlocked Threads!",

		admin_settings: "Settings",
		admin_settings_desc: "Survey tool settings",
		adminSettingsChangeTemp: "Temporary change:",
		appendInputBoxChange: "Change",
		appendInputBoxCancel: "Clear",

		userlevel_admin: "Admin",
		userlevel_tc: "TC",
		userlevel_expert: "Expert",
		userlevel_vetter: "Vetter",
		userlevel_street: "Guest",
		userlevel_locked: "Locked",
		userlevel_manager: "Manager",

		userlevel_admin_desc: "Administrator",
		userlevel_tc_desc: "CLDR-Technical Committee member",
		userlevel_expert_desc: "Language Expert",
		userlevel_vetter_desc: "Regular Vetter",
		userlevel_street_desc: "Guest User",
		userlevel_manager_desc: "Project Manager",
		userlevel_locked_desc: "Locked User, no login",

		admin_threads: "Threads",
		admin_threads_desc: "All Threads",
		adminClickToViewThreads: "Click a thread to view its call stack",

		admin_exceptions: "Exception Log",
		admin_exceptions_desc: "Contents of the exceptions.log",
		adminClickToViewExceptions: "Click an exception to view its call stack",

		adminExceptionSQL_desc: "SQL state and code",
		adminExceptionSTACK_desc: "Exception call stack",
		adminExceptionMESSAGE_desc: "Exception message",
		adminExceptionUptime_desc: "ST uptime at stack time",
		adminExceptionHeader_desc: "Overall error message and cause",
		adminExceptionLogsite_desc: "Location of logException call",
		adminExceptionDup: "(${0} other time(s))",
		last_exception: "(last exception)",
		more_exceptions: "(more exceptions...)",
		no_exceptions: "(no exceptions.)",
		adminExceptionDupList: "List of other instances:",
		clickToSelect: "select",

		admin_ops: "Actions",
		admin_ops_desc: "Administrative Actions",

		notselected_desc: '',

		recentLoc: "Locale",
		recentXpath: "XPath",
		recentValue: "Value",
		recentWhen: "When",
		recentOrg: "Organization",
		recentNone: "No items to show.",
		recentCount: "Count",
		downloadXmlLink: "Download XML...",

		testOkay: "has no errors or warnings",
		testWarn: "has warnings",
		testError: "has errors",

		voTrue: "You have already voted on this item.",
		voFalse: "You have not yet voted on this item.",

		online: "Online",
		disconnected: "Disconnected",
		error_restart: "(May be due to Survey Tool restart on server)",
		error: "Disconnected: Error",
		details: "Details...",
		startup: "Starting up...",

		admin_users: "Users",
		admin_users_desc: "Currently logged-in users",
		admin_users_action_kick: "Kick",
		admin_users_action_kick_desc: "Logout this user",

		// pClass ( see DataSection.java)
		pClass_winner: "This item is currently winning.",
		pClass_alias: "This item is aliased from another location.",
		pClass_fallback_code: "This item is an untranslated code.",
		pClass_fallback_root: "This item is inherited from the root locale.",
		pClass_loser: "This is a proposed item which is not currently winning.",
		pClass_fallback: "This item is inherited.", //  ${inheritFromDisplay}.", - removed in r8801
		pClassExplain_desc: "This area shows the item's status.",
		
		override_explain_msg: "You have voted for this item with ${overrideVotes} votes instead of the usual ${votes}",
		voteInfo_overrideExplain_desc: "",
		mustflag_explain_msg: "The item you voted for is not winning. However, you may post a forum entry to flag the item for Committee review.",
		voteInfo_mustflag_explain_desc: "",
		flag_desc: "This item has been flagged for review by the CLDR Technical Committee.",
		flag_d_desc: "Losing items may be flagged for CLDR Committee review.",
		explainRequiredVotes: "Changes to this item require ${requiredVotes} votes.",
		xpath_desc: "This is the XPath denoting the currently clicked item. For more information, see http://cldr.unicode.org (click to select)",

		winningStatus_disputed: "Disputed",
		winningStatus_msg:  "${1} ${0} Value ",
		lastReleaseStatus_msg: "${0} Last Release Value ",
		lastReleaseStatus1_msg: "",
		
		reportGuidance: " ",
		dataPageInitialGuidance: "Please consult the <a target='_blank' href='http://cldr.unicode.org/index/survey-tool/guide'>Instructions <span class='glyphicon glyphicon-share'></span></a> page.<br/><br/>Briefly, for each row:<br/><ol><li>Click on a cell in the 'Code' column.</li><li>Read the details that appear in the right panel (widen your window to see it).</li><li> Hover over the English and the Winning value to see examples.</li><li>To vote:<ol><li>for an existing item in the Winning or Others column, click on the <input type='radio'/> for that item.</li><li>for a new value, click on the button in the \"Add\" column. A new editing box will open. Enter the new value and hit RETURN.</li><li>for no value (abstain, or retract a vote), click on the  <input type='radio'/> in the Abstain column.</li></ol></li></ol>",
		generalPageInitialGuidance: "This area will show details of items as you work with the Survey Tool.",
		localesInitialGuidance: "Choose a locale to get started.  <ul><li><span class='locked'>locked</span> locales may not be modified by anyone,</li><li><span class='canmodify'>hand icon</span> indicates editing allowed by you</li><li><span class='name_var'>Locales with (Variants)</span> may have specific differences to note.</li></ul><p>Don't see your locale? See: <a href='http://cldr.unicode.org/index/bug-reports#New_Locales'>Adding New Locales</a></p>",
		
		loginGuidance: "You may not make any changes, you are not logged in.",
		readonlyGuidance: "You may not make changes to this locale.",

		htmlvorg: "Org",
		htmlvorgvote: "Organization's vote",
		htmlvdissenting: "Dissenting Votes",	   
		flyovervorg: "List of Organizations",
		flyovervorgvote: "The final vote for this organization",
		flyovervdissenting: "Other votes cast against the final vote by members of the organization",
		voteInfoScorebox_msg: "${0}: ${1}",
		voteInfo_established_url: "http://cldr.unicode.org/index/process#TOC-Draft-Status-of-Optimal-Field-Value",
		voteInfo_orgColumn: "Org.",
		voteInfo_noVotes: "(no votes)",
		voteInfo_iconBar_desc: "This area shows the status of each candidate item.",
		voteInfo_noVotes_desc: "There were no votes for this item.",
		voteInfo_key: "Key:",
		voteInfo_valueTitle_desc: "Item's value",
		voteInfo_orgColumn_desc: "Which organization is voting",
		voteInfo_voteTitle_desc: "The total vote score for this value",
		voteInfo_orgsVote_desc: "This vote is the organization's winning vote",
		voteInfo_orgsNonVote_desc: "This vote is not the organization's winning vote",
		voteInfo_lastRelease_desc: "This mark shows on the item which was approved in the last release, if any.",
		voteInfo_lastReleaseKey_desc: "This mark shows on the item which was approved in the last release, if any.",
		voteInfo_winningItem_desc: "This mark shows the item which is currently winning.",
		voteInfo_winningKey_desc: "This mark shows the item which is currently winning.",
		voteInfo_perValue_desc: "This shows the state and voters for a particular item.",
		voteInfo_moreInfo: "Click here for a full explanation of the icons and their meanings.",
		// CheckCLDR.StatusAction 
		StatusAction_msg:              "Not submitted: ${0}",
		StatusAction_popupmsg:         "Sorry, your vote for '${1}' could not be submitted: ${0}", // same as StatusAction_msg but with context
		StatusAction_ALLOW:            "(Actually, it was allowed.)", // shouldn't happen
		StatusAction_FORBID:           "Forbidden.",
		StatusAction_FORBID_ERRORS:    "The item had errors.",
		StatusAction_FORBID_READONLY:  "The item is read-only.",
		StatusAction_FORBID_COVERAGE:  "The item is not visible by coverage rules.",

		// v.jsp
		"v-title2_desc": "Locale title",
		v_bad_special_msg:  "Bad URL (mistyped?), unknown special action: \"${special}\"",
		v_oldvotes_title: "Old Votes - from before ${votesafter}",
		v_oldvotes_count_msg: "Winning Vote Count: ${uncontested}, Losing Vote Count: ${contested}",
		v_oldvotes_bad_msg: "You have ${bad} ignored items. These have been removed from or restructured in CLDR, and may not be imported.",
		v_oldvotes_only_bad_msg: "You have only ${bad} ignored items. These have been removed from or restructured in CLDR, and may not be imported. You may consider this locale complete.",
		v_oldvotes_title_uncontested: "Winning Votes",
		v_oldvotes_desc_uncontested_msg: "These are your votes which agreed with the winning value in previous CLDR ${version}.",
		v_oldvotes_title_contested: "Losing Votes",
		v_oldvotes_desc_contested_msg: "These are your votes which did not agree with the winning value in previous CLDR ${version}. You may choose to import them if you believe they still represent the best value.",
		v_oldvotes_locale_list_help_msg: "Here is a list of locales which you have voted for in CLDR ${version} and previous. Click one to review and import these votes from the previous CLDR version. If you have already reviewed these locales, you may click “No and don't ask again” the next time the pop-up dialog appears. Note that some of the locales listed may have votes which are no longer valid in CLDR. Also note that this import function is only available during the data submission phase.",
		v_oldvotes_return_to_locale_list: "Return to List of Locales with old votes",
		v_oldvotes_path: "Path",
		v_oldvotes_locale_msg: "These are your winning and losing votes for CLDR ${version} in ${locale}. Expand the section you want to work with, select or deselect items by clicking, and import votes.  By default, your old winning votes are selected for import, while your old losing votes are not.",
		"v-oldvotes-loc-help_desc": "Specific help on this locale's old votes",
		"v-oldvotes-desc_desc": "Specific help on this type of vote",
		"v-accept_desc": "Checked items will be imported, unchecked items will not be imported.",
		"code_desc": "The short code for this item. ",
		"v-path_desc": "The short code for this item. Click here to view the item, in a new window.",
		"v-comp_desc": "The comparison value (English)",
		"v-win_desc": "This was the winning value for previous CLDR",
		"v-mine_desc": "This was your vote from the previous CLDR",
		"pathChunk_desc": "This header separates common items",
		v_oldvotes_winning_msg: "CLDR ${version} winning",
		v_oldvotes_mine: "My old vote",
		v_oldvotes_accept: "Import?",
		v_oldvotes_all: "Choose All",
		v_oldvotes_go: "view",
		v_oldvotes_hide: "Close this section",
		v_oldvotes_show: "Show: ",
		v_oldvotes_none: "Choose None",
		v_oldvotes_no_contested: "No losing votes.",
		v_oldvotes_no_old_here: "No old votes to import. You're done with this locale!",
		v_oldvotes_no_old: "No old votes to import. You're done with old votes!",
		v_submit_msg: "Vote for selected ${type}",
		v_submit_busy: "Submitting...",

		v_oldvote_remind_msg: "CLDR: Old Votes Reminder Message",
		v_oldvote_remind_desc_msg: "You currently have ${count} votes from previous CLDR vetting periods. Would you like to view them for import into the current release?<p>  (Note: Until the Data Submission phase ends, you can review and import these votes later via the '<span class=notselected>Manage</span>' link once logged in.)",
		v_oldvote_remind_yes: "Yes, review/import old votes now",
		v_oldvote_remind_no: "No, not today",
		v_oldvote_remind_dontask: "No, and don't ask again",
		"v-title_desc": "This area shows the date before which votes are considered “old”.",
		special_oldvotes: "Import Old Votes",
		special_locales: "Locale List",
		section_general: "General Info",
		section_forum: "Forum",
		section_subpages: "Subpages",
		special_search:  "Search",
		special_statistics: "Statistics",
		special_r_compact: "Numbers",
		special_r_datetime: "Datetime",
		special_r_zones: "Zones",
		searchNoResults: "No results found.",
		searchGuidance: "This is a basic search facility. An exact word such as 'Monday' or 'Montag' can be entered, or an XPath or string ID like 'eeaf1f975877a5d'.  An optional locale ID can be prefixed to any search term, so 'mt:Monday' or 'mt:eeaf1f975877a5d'.",
		section_help: "Choose an item from the 'Subpages' menu to begin working with this section.",
		
		statisticsGuidance: "This shows some basic statistics. More information is currently available under the 'Manage' menu.",
		
        section_info_Core_Data:  "The Core Data is vital for proper functioning of each locale. Because changes can disrupt the survey tool, data can only be changed via tickets. Please also review the Plural Rules for your locale: they are also vital.",
        section_info_Locale_Display_Names:  "The Locale Display Names are used to format names of locales, languages, scripts, and regions (including countries).",
        section_info_DateTime:  "The Date and Time data is used to format dates and times, including intervals (eg, 'Dec 10-12'). After completing this section, you should review the overall results with Review: Date/Time.",
        section_info_Timezones:  "The Timezones data is used to display timezones in a variety of ways. They also contain a list of cities associated with timezones. After completing this section, you should review the overall results with Review: Zones.",
        section_info_Numbers:  "The Numbers data is used to format numbers and currencies, including compact numbers (eg, '3M' for 3,000,000). After completing this section, you should review the overall results with Review: Numbers.",
        section_info_Currencies:  "The Currencies data is used to format the names of currencies, and also provides the various currency symbols. After completing this section, you should review the overall results with Review: Numbers.",
        section_info_Units:  "The Units is used for formatting measurements, such as '3 hours' or '4 kg'.",
        section_info_Misc:  "The Miscellaneous data is used to some special purpose items, such as lists (eg, 'A, B, and C') and truncated strings (eg, 'supercalifrag…cious').",
		
		forumNewPostButton: "New Forum Post",
		forumNewButton_desc: "Clicking this will bring up a form to reply to this particular item, in a new window. Click 'view item' after submitting to return to this item.",
		forumNewPostFlagButton: "Flag for Review",
		forumNewPostFlagButton_desc: "Clicking this will bring up a form to reply to this particular item, in a new window. Click 'view item' after submitting to return to this item.",
		
		special_general: "Please hover over the sidebar to choose a section to begin entering data. If you have not already done so, please read the <a target='_blank' href='http://www.unicode.org/cldr/survey_tool.html'>Instructions</a>, particularly the Guide and the Walkthrough. You can also use the Dashboard to see all the errors, warnings, and missing items in one place.",

		defaultContent_msg: "This locale, ${info.name}  is the <i><a target='CLDR-ST-DOCS' href='http://cldr.unicode.org/translation/default-content'>default content locale</a></i> for <b><a class='notselected' href='#/${info.dcParent}'>${dcParentName}</a></b>, and thus editing or viewing is disabled. ",
		defaultContentChild_msg: "This locale, ${info.name}  supplies the <i><a target='CLDR-ST-DOCS' href='http://cldr.unicode.org/translation/default-content'>default content</a></i> for <b><a class='notselected' href='#/${info.dcChild}'>${dcChildName}</a></b>. Please make sure that all the changes that you make here are appropriate for <b>${dcChildName}</b>. If there are multiple acceptable choices, please try to pick the one that would work for the most sublocales. ",           
		defaultContent_header_msg: "= ${dcChild}",
		defaultContent_titleLink: "content",
		readonly_msg: "This locale may not be edited.<br/> ${msg}",
		readonly_unknown: "Reason: Administrative Policy.",
		beta_msg: "The SurveyTool is currently in Beta. Any data added here will NOT go into CLDR.",
		sidewaysArea_desc: "view of what the votes are in other, sister locales",
		sideways_loading0: " ",
		sideways_loading1: "Comparing to other locales...",
		sideways_same: "Other locales have the same value.",
		sideways_diff: "Other locales have different values!",
		sideways_noValue: "(no value)",
		

		ari_message: 'Problem with the SurveyTool',
		ari_sessiondisconnect_message: "Your session has been disconnected.",
		ari_force_reload: '[Second try: will force page reload]',

		coverage_auto_msg: '${surveyOrgCov} (Default)',
		coverage_core: 'Core',
		coverage_posix: 'POSIX',
		coverage_minimal: 'Minimal',
		coverage_basic: 'Basic',
		coverage_moderate: 'Moderate',
		coverage_modern: 'Modern',
		coverage_comprehensive: 'Comprehensive',
		coverage_optional: 'Optional',

		coverage_menu_desc: 'Change the displayed coverage level. "Automatic" will use your organization\'s preferred value for this locale, if any.',

		section_mail: 'Messages',
		
		
		jsonStatus_msg: "You should see your content shortly, thank you for waiting. By the way, there are ${users} logged-in users and ${guests} visitors to the Survey Tool. The server's workload is about ${sysloadpct} of normal capacity. You have been waiting about ${waitTime} seconds.",
		err_what_section: "load part of this locale",
		err_what_locmap: "load the list of locales",
		err_what_menus: "load the Survey Tool menus",
		err_what_status: "get the latest status from the server",
		err_what_unknown: "process your request",
		err_what_oldvotes: "fetch or import your old votes",
		err_what_vote: "vote for a value",
		E_UNKNOWN: "An error occured while trying to '${what}', and the error code is '${code}'.\n Reloading may resume your progress.",
		E_INTERNAL: "An internal error occured trying to '${what}'. This is probably a bug in the SurveyTool.",
		E_BAD_SECTION: "An error occured while trying to ${what}, the server could not find what was requested. \nPerhaps the URL is incorrect?",
		E_BAD_LOCALE: "The locale, '${surveyCurrentLocale}',\n does not exist. It was either mistyped or has not been added to the Survey Tool.",
		E_NOT_STARTED: "The SurveyTool is still starting up. Please wait a bit and hit Reload.",
		E_SPECIAL_SECTION: "An error occured while trying to ${what}, the server said that those items aren't visible in the Survey Tool.\nPerhaps the URL is incorrect or an item was deprected?",
		E_SESSION_DISCONNECTED: "Your session has timed out or the SurveyTool has restarted. To continue from where you were, hit Reload.",
		E_DISCONNECTED: "You were disconnected from the SurveyTool. To reconnect, hit Reload.",
		E_NO_PERMISSION: "You do not have permission to do that operation.",
		E_NOT_LOGGED_IN: "That operation cannot be done without being logged in.",
		E_BAD_VALUE: "The vote was not accepted: ${err_data.message}",
		E_BAD_XPATH: "This item does not exist in this locale.",
		"": ""})
//		"mt-MT": false

		// sublocales
});
