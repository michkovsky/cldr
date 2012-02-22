/*
 ******************************************************************************
 * Copyright (C) 2004-2011, International Business Machines Corporation and   *
 * others. All Rights Reserved.                                               *
 ******************************************************************************
 */
package org.unicode.cldr.web;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.unicode.cldr.icu.LDMLConstants;
import org.unicode.cldr.test.CheckCLDR;
import org.unicode.cldr.test.DisplayAndInputProcessor;
import org.unicode.cldr.test.ExampleGenerator;
import org.unicode.cldr.test.ExampleGenerator.HelpMessages;
import org.unicode.cldr.tool.ShowData;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CLDRFile.DraftStatus;
import org.unicode.cldr.util.CLDRLocale;
import org.unicode.cldr.util.CachingEntityResolver;
import org.unicode.cldr.util.CldrUtility;
import org.unicode.cldr.util.Factory;
import org.unicode.cldr.util.LDMLUtilities;
import org.unicode.cldr.util.PathUtilities;
import org.unicode.cldr.util.SimpleFactory;
import org.unicode.cldr.util.StackTracker;
import org.unicode.cldr.util.StandardCodes;
import org.unicode.cldr.util.SupplementalData;
import org.unicode.cldr.util.SupplementalDataInfo;
import org.unicode.cldr.util.VoteResolver;
import org.unicode.cldr.util.XMLSource;
import org.unicode.cldr.util.XPathParts;
import org.unicode.cldr.web.CLDRDBSourceFactory.DBEntry;
import org.unicode.cldr.web.SurveyAjax.AjaxType;
import org.unicode.cldr.web.SurveyThread.SurveyTask;
import org.unicode.cldr.web.UserRegistry.InfoType;
import org.unicode.cldr.web.UserRegistry.User;
import org.unicode.cldr.web.Vetting.DataSubmissionResultHandler;
import org.unicode.cldr.web.WebContext.HTMLDirection;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.ibm.icu.dev.test.util.BagFormatter;
import com.ibm.icu.dev.test.util.CollectionUtilities;
import com.ibm.icu.dev.test.util.ElapsedTimer;
import com.ibm.icu.dev.test.util.TransliteratorUtilities;
import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;

/**
 * The main servlet class of Survey Tool
 */
public class SurveyMain extends HttpServlet implements CLDRProgressIndicator {

	public static final String QUERY_SAVE_COOKIE = "save_cookie";

    private static final String STEPSMENU_TOP_JSP = "stepsmenu_top.jsp";

	private static final String REPORT_PREFIX = "r_";

    private static final String R_STEPS = REPORT_PREFIX+ "steps";
    public static final String R_VETTING = REPORT_PREFIX+ "vetting";

    public static final String SURVEYMAIN_REVISION = "$Revision$";

    private static final String CLDR_BULK_DIR = "CLDR_BULK_DIR";
	private static String bulkStr = null;
	static final String ACTION_DEL = "_del";
    static final String ACTION_UNVOTE = "_unvote";
    private static final String XML_CACHE_PROPERTIES = "xmlCache.properties";
    /**
     * 
     */
    private static final long serialVersionUID = -3587451989643792204L;

    /**
     * This class enumerates the current phase of the survey tool
     * @author srl
     *
     */
	public enum Phase { 
        SUBMIT("Data Submission"),           // SUBMISSION
        VETTING("Vetting"),
        VETTING_CLOSED("Vetting Closed"),   // closed after vetting - open for admin
        CLOSED("Closed"),           // closed
        DISPUTED("Dispute Resolution"),
        FINAL_TESTING("Final Testing"),    //FINAL_TESTING
        READONLY("Read-Only"),
        BETA("Beta");
        
        private String what;
        private Phase(String s) {
            what = s;
        }
        public String toString() {
            return what;
        }
    }; 
    
    // ===== Configuration state
	private static final boolean DEBUG=CldrUtility.getProperty("TEST", false);
    private static Phase currentPhase = null; /** set by CLDR_PHASE property. **/
    private static String oldVersion = "OLDVERSION";
    private static String newVersion = "NEWVERSION";
    public static       boolean isUnofficial = true;  /** set to true for all but the official installation of ST. **/

    // ==== caches and general state
    
    public UserRegistry reg = null;
    public XPathTable   xpt = null;
    public Vetting      vet = null;
    public SurveyForum  fora = null;
    private CLDRDBSourceFactory dbsrcfac = null;
    public LocaleChangeRegistry lcr = new LocaleChangeRegistry();
    static ElapsedTimer uptime = new ElapsedTimer("uptime: {0}");
    ElapsedTimer startupTime = new ElapsedTimer("{0} until first GET/POST");
    public static String isBusted = null;
    private static String isBustedStack = null;
    private static Throwable isBustedThrowable = null;
    private static ElapsedTimer isBustedTimer = null;
    static ServletConfig config = null;
    public static OperatingSystemMXBean osmxbean = ManagementFactory.getOperatingSystemMXBean();

    
    // ===== UI constants
    static final String CURRENT_NAME="Others";

    // ===== Special bug numbers.
//    public static final String BUG_METAZONE_FOLDER = "data";
//    public static final int    BUG_METAZONE = 1262;
    //public static final String BUG_ST_FOLDER = "tools";
    //public static final int    BUG_ST = xxxx; // was: umbrella bug
    public static final String URL_HOST = "http://www.unicode.org/";
    public static final String URL_CLDR = URL_HOST+"cldr/";
    public static final String BUG_URL_BASE = URL_CLDR+"trac";
    public static final String GENERAL_HELP_URL = URL_CLDR+"survey_tool.html";
    public static final String GENERAL_HELP_NAME = "General&nbsp;Instructions";
    
    // ===== url prefix for help
//    public static final String CLDR_WIKI_BASE = URL_CLDR+"wiki";
//    public static final String CLDR_HELP_LINK = CLDR_WIKI_BASE+"?SurveyToolHelp";
//    public static final String CLDR_HELP_LINK_EDIT = CLDR_HELP_LINK;
    public static final String CLDR_HELP_LINK = GENERAL_HELP_URL+"#";
     
    public static final String SLOW_PAGE_NOTICE = ("<i>Note: The first time a page is loaded it may take some time, please be patient.</i>");    

    // ===== Hash keys and field values
    public static final String SUBVETTING = "//v";
    public static final String SUBNEW = "//n"; 
    public static final String NOCHANGE = "nochange";
    public static final String CURRENT = "current";
    public static final String PROPOSED = "proposed";
    public static final String NEW = "new";
    public static final String DRAFT = "draft";
    public static final String UNKNOWNCHANGE = "Click to suggest replacement";
    public static final String DONTCARE = "abstain";
    public static final boolean HAVE_REMOVE = false; /**< Experimental support for 'remove' */
    public static final String REMOVE = "remove";
    public static final String CONFIRM = "confirm";
    public static final String INHERITED_VALUE = "inherited-value";
    public static final String CHANGETO = "change to";
    public static final String PROPOSED_DRAFT = "proposed-draft";
    public static final String MKREFERENCE = "enter-reference";
    public static final String STFORM = "stform";
    //public static final String MODIFY_THING = "<span title='You are allowed to modify this locale.'>\u270D</span>";             // was: F802
    //public static final String MODIFY_THING = "<img src='' title='You are allowed to modify this locale.'>";             // was: F802
    static String modifyThing(WebContext ctx) {
        return "&nbsp;"+ctx.modifyThing("You are allowed to modify this locale.");
    }
    
    // ========= SYSTEM PROPERTIES
    public static  String vap = System.getProperty("CLDR_VAP"); // Vet Access Password
    public static  String vetdata = System.getProperty("CLDR_VET_DATA"); // dir for vetted data
    File vetdir = null;
    public static  String vetweb = System.getProperty("CLDR_VET_WEB"); // dir for web data
    public static  String cldrLoad = System.getProperty("CLDR_LOAD_ALL"); // preload all locales?
    public static String fileBase = null; // not static - may change later. Common dir
    public static String fileBaseSeed = null; // not static - may change later. Seed dir
    private static String fileBaseOld = null; // fileBase + oldVersion
    static String specialMessage = System.getProperty("CLDR_MESSAGE"); //  static - may change later
    static String specialHeader = System.getProperty("CLDR_HEADER"); //  static - may change later
    public static String lockOut = System.getProperty("CLDR_LOCKOUT"); //  static - may change later
    static long specialTimer = 0; // 0 means off.  Nonzero:  expiry time of countdown.
    
    public static java.util.Properties survprops = null;
    public static String cldrHome = null;
    public static File homeFile = null;

    
            
//    public static final String LOGFILE = "cldr.log";        // log file of all changes

    // ======= query fields
    public static final String QUERY_TERRITORY = "territory";
    public static final String QUERY_ZONE = "zone";
    public static final String QUERY_PASSWORD = "pw";
    static final String QUERY_PASSWORD_ALT = "uid";
    public static final String QUERY_EMAIL = "email";
    public static final String QUERY_SESSION = "s";
    public static final String QUERY_LOCALE = "_";
    public static final String QUERY_SECTION = "x";
    public static final String QUERY_EXAMPLE = "e";
    public static final String QUERY_DO = "do";
	
    static final String SURVEYTOOL_COOKIE_SESSION = CookieSession.class.getPackage().getName()+".id";
    static final String SURVEYTOOL_COOKIE_NONE = "0";
    static final String PREF_SHOWCODES = "p_codes";
    static final String PREF_SORTMODE = "p_sort";
    static final String PREF_SHOWUNVOTE = "p_unvote";
    static final String PREF_SHOWLOCKED = "p_showlocked";
    static final String PREF_NOPOPUPS = "p_nopopups";
    static final String PREF_CODES_PER_PAGE = "p_pager";
    static final String PREF_XPID = "p_xpid";
    static final String PREF_GROTTY = "p_grotty";
    static final String PREF_SORTMODE_CODE = "code";
    static final String PREF_SORTMODE_CODE_CALENDAR = "codecal";
    static final String PREF_SORTMODE_METAZONE = "metazon";
   // static final String PREF_SORTMODE_ALPHA = "alpha";
    static final String PREF_SORTMODE_WARNING = "interest";
    static final String PREF_SORTMODE_NAME = "name";
    static final String PREF_SORTMODE_DEFAULT = PREF_SORTMODE_CODE;
//	static final String PREF_SHOW_VOTING_ALTS = "p_vetting_details";
    static final String PREF_NOSHOWDELETE = "p_nodelete";
    static final String PREF_DELETEZOOMOUT = "p_deletezoomout";
    public static final String PREF_NOJAVASCRIPT = "p_nojavascript";
    static final String PREF_ADV = "p_adv"; // show advanced prefs?
    static final String PREF_XPATHS = "p_xpaths"; // show xpaths?
    public static final String PREF_LISTROWS = "p_listrows";
    public static final String PREF_DEBUGJSP = "p_debugjsp"; // debug JSPs?
    public static final String PREF_COVLEV = "p_covlev"; // covlev
    public static final String PREF_COVTYP = "p_covtyp"; // covtyp
    //    static final String PREF_SORTMODE_DEFAULT = PREF_SORTMODE_WARNING;
    
    static final String  BASELINE_ID = "en_ZZ"; // Needs to be en_ZZ as per cldrbug #2918
    public static final ULocale BASELINE_LOCALE = new ULocale(BASELINE_ID);
    public static final String  BASELINE_LANGUAGE_NAME = BASELINE_LOCALE.getDisplayLanguage(BASELINE_LOCALE); // Note: Only shows language.
    
    public static final String METAZONE_EPOCH = "1970-01-01";
  
    // ========== lengths
    /**
     * @see WebContext#prefCodesPerPage()
     */
    static final int CODES_PER_PAGE = 80;  // This is only a default.
    static final int PAGER_SHORTEN_WIDTH = 25   ; // # of chars in the 'pager' list before they are shortened
    static final int REFS_SHORTEN_WIDTH = 120;


    static final String MEASNAME = "measurementSystemName";
    static final String CODEPATTERN = "codePattern";
    public static final String CURRENCYTYPE = "//ldml/"+PathUtilities.NUMBERSCURRENCIES+"/currency[@type='";
    public static String xMAIN = "general";
    public static String xREMOVE = "REMOVE";

    public static final String GREGORIAN_CALENDAR = "gregorian calendar";
    public static final String OTHER_CALENDARS = "other calendars";
    // 
    public static String CALENDARS_ITEMS[] = PathUtilities.getCalendarsItems();
    public static String METAZONES_ITEMS[] = PathUtilities.getMetazonesItems();

    public static final String OTHERROOTS_ITEMS[] = {
        LDMLConstants.CHARACTERS,
        LDMLConstants.NUMBERS,
        LDMLConstants.LOCALEDISPLAYPATTERN,
        "units",
        PathUtilities.xOTHER
    };

    public static final String GREGO_XPATH = "//ldml/dates/"+LDMLConstants.CALENDARS+"/"+LDMLConstants.CALENDAR+"[@type=\"gregorian\"]";
    public static final String RAW_MENU_ITEM = "raw";
    public static final String TEST_MENU_ITEM = "test";
    
    public static final String SHOWHIDE_SCRIPT = "<script type='text/javascript'><!-- \n" +
                                                "function show(what)\n" +
                                                "{document.getElementById(what).style.display=\"block\";\ndocument.getElementById(\"h_\"+what).style.display=\"none\";}\n" +
                                                "function hide(what)\n" +
                                                "{document.getElementById(what).style.display=\"none\";\ndocument.getElementById(\"h_\"+what).style.display=\"block\";}\n" +
                                                "--></script>";
    
    static final HelpMessages surveyToolSystemMessages = new HelpMessages("st_sysmsg.html");

    
    static String sysmsg(String msg) {
        try {
            return surveyToolSystemMessages.find(msg);
        } catch(Throwable t) {
            SurveyLog.logger.warning("Err " + t.toString() + " while trying to load sysmsg " + msg);
            return "[MISSING MSG: " + msg+"]";
        }
    }
    /**
     * Servlet initializer
     */
    
    public static SurveyMain getInstance(HttpServletRequest req) {
        if(config == null) return null; // not initialized.
        return(SurveyMain)config.getServletContext().getAttribute(SurveyMain.class.getName());
    }
    private void setInstance(HttpServletRequest req) {
        config.getServletContext().setAttribute(SurveyMain.class.getName(), this);
    }
    
    
    public final void init(final ServletConfig config)
    throws ServletException {
        new com.ibm.icu.text.SimpleDateFormat(); // Ensure that ICU is available before we get any farther.
        super.init(config);
        cldrHome = config.getInitParameter("cldr.home");
        this.config = config;
        
        startupThread.addTask(new SurveyThread.SurveyTask("startup") {
            public void run() throws Throwable{
                doStartup();
            }
        });
        
        startupThread.start();
        SurveyLog.logger.warning("Startup thread launched");
    }

    public SurveyMain() {
    	CookieSession.sm = this;
    	try {
    		dbUtils  = DBUtils.getInstance();
    	} catch(Throwable t) {
    		this.busted("Error starting up database - see <a href='http://cldr.unicode.org/development/running-survey-tool/cldr-properties/db'> http://cldr.unicode.org/development/running-survey-tool/cldr-properties/db </a>", t);
    	}
    }

    /**
    * output MIME header, build context, and run code..
     */
    public void doPost(HttpServletRequest request, HttpServletResponse response)    throws IOException, ServletException
    {
        doGet(request,response);
    }

    /**
     * IP blacklist
     */
    Hashtable<String, Object> BAD_IPS = new Hashtable<String,Object>();
    
    public void doGet(HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        if(!ensureStartup(request, response)) {
            return;
        }
        
        if(!isBusted()) {
            if(startupTime != null) {
                String startupMsg = null;
                startupMsg = (startupTime.toString());
    //            logger.info(startupMsg);  // log how long startup took
                startupTime = null;
            }
            
            String remoteIP = WebContext.userIP(request);
            
            response.setHeader("Cache-Control", "no-cache");
            response.setDateHeader("Expires",0);
            response.setHeader("Pragma","no-cache");
            response.setDateHeader("Max-Age",0);
            response.setHeader("Robots", "noindex,nofollow");
            
            // handle raw xml
            try {
	            if(doRawXml(request,response)) {
	                // not counted.
	                xpages++;
	                return; 
	            }
            } catch(Throwable t) {
               	SurveyLog.logger.warning("Error on doRawXML: " + t.toString());
               	t.printStackTrace();
               	response.setContentType("text/plain");
               	ServletOutputStream os = response.getOutputStream();
               	os.println("Error processing raw XML:\n\n");
               	t.printStackTrace(new PrintStream(os));
            	xpages++;
            	return;
            }
            pages++;
            
            if((pages % 100)==0) {
                freeMem(pages,xpages);        
            }
        }
        com.ibm.icu.dev.test.util.ElapsedTimer reqTimer = new com.ibm.icu.dev.test.util.ElapsedTimer();
        
        /**
         * Busted: unrecoverable error, do not attempt to go on.
         */
        if(isBusted()) {
            String pi = request.getParameter("sql"); // allow sql
            if((pi==null) || (!pi.equals(vap))) {
                response.setContentType("text/html; charset=utf-8");
                PrintWriter out = response.getWriter();
                out.println("<html>");
                out.println("<head>");
                out.println("<title>CLDR Survey Tool offline</title>");
                out.println("<link rel='stylesheet' type='text/css' href='"+ request.getContextPath() + "/" + "surveytool.css" + "'>");
                out.println(SHOWHIDE_SCRIPT);
                SurveyAjax.includeAjaxScript(request, response, SurveyAjax.AjaxType.STATUS);
                out.println("<script type=\"text/javascript\">timerSpeed = 60080;</script>"); // don't flood server if busted- check every minute.
                out.print("<div id='st_err'><!-- for ajax errs --></div><span id='progress'>");
                out.print(getTopBox());
                out.println("</span>");
//                showSpecialHeader(out);
//                out.println("<h1>The CLDR Survey Tool is offline</h1>");
//                out.println("<div class='ferrbox'><pre>" + isBusted +"</pre><hr>");
//                out.println("\n");
//                out.println(getShortened((SurveyForum.HTMLSafe(isBustedStack).replaceAll("\t", "&nbsp;&nbsp;&nbsp;").replaceAll("\n", "<br>"))));
//                out.println("</div><br>");
                
                
                out.println("<hr>");
//                if(!isUnofficial) {
//                    out.println("Please try this link for info: <a href='"+CLDR_WIKI_BASE+"?SurveyTool/Status'>"+CLDR_WIKI_BASE+"?SurveyTool/Status</a><br>");
//                    out.println("<hr>");
//                }
                out.println("An Administrator must intervene to bring the Survey Tool back online. <br> " + 
                            " <i>This message has been viewed " + pages + " time(s), SurveyTool has been down for " + isBustedTimer + "</i>");

//                if(false) { // dump static tables.
//                    response.setContentType("application/xml; charset=utf-8");
//                    WebContext xctx = new WebContext(request,response);
//                    xctx.staticInfo();
//                    xctx.close();
//                }
                return;        
            }
        }
        
        /** User database request
         * 
         */
        if(request.getParameter("udump") != null &&
            request.getParameter("udump").equals(vap)) {  // XML.
            response.setContentType("application/xml; charset=utf-8");
            WebContext xctx = new WebContext(request,response);
            doUDump(xctx);
            xctx.close();
            return;
        }
        
        // rest of these are HTML
        response.setContentType("text/html; charset=utf-8");

        // set up users context object
        
        WebContext ctx = new WebContext(request,response);
        ctx.reqTimer = reqTimer;
        ctx.sm = this;
        
        /*
        String theIp = ctx.userIP();
        if(theIp.equals("66.154.103.161") // gigablast
          ) {
            try {
                Thread.sleep(98765);
            } catch(InterruptedException ie) {
            }
        }*/
        
        String baseThreadName = Thread.currentThread().getName();
        
        try {
	
            
            if(isUnofficial) {
                boolean waitASec = twidBool("SurveyMain.twoSecondPageDelay");
                if(waitASec) {
                    ctx.println("<h1>twoSecondPageDelay</h1>");
                    Thread.sleep(2000);
                }
            }

	        if(ctx.field("dump").equals(vap)) {
	        	Thread.currentThread().setName(baseThreadName+" ST admin");
	            doAdminPanel(ctx); // admin interface
	        } else if(ctx.field("sql").equals(vap)) {
	        	Thread.currentThread().setName(baseThreadName+" ST sql");
	            doSql(ctx); // SQL interface
	        } else {
	        	Thread.currentThread().setName(baseThreadName+" ST ");
	        	doSession(ctx); // Session-based Survey main
	        }
        } catch (Throwable t) {
        	SurveyLog.logException(t, ctx);
        	ctx.println("<div class='ferrbox'><h2>Error processing session: </h2><pre>" + t.toString()+"</pre></div>");
        	SurveyLog.logger.warning("Failure with user: " + t);
        	t.printStackTrace();
        } finally {
        	Thread.currentThread().setName(baseThreadName);
	        ctx.close();
        }
    }
    
    /**
     * Make sure we're started up, otherwise tell 'em, "please wait.."
     * @param request
     * @param response
     * @return true if started, false if we are not (on false, get out, we're done printing..)
     * @throws IOException
     * @throws ServletException 
     */
    private boolean ensureStartup(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        setInstance(request);
        if(!isSetup) {
            boolean isGET = "GET".equals(request.getMethod());
            int sec = 600; // was 4
            if(isBusted != null) {
                sec = 300;
            }
            String base = WebContext.base(request);
            String loadOnOk = base;
            if(isGET) {
                String qs  = "";
                String pi = "";
                if(request.getPathInfo()!=null&&request.getPathInfo().length()>0) {
                    pi = request.getPathInfo();
                }
                if(request.getQueryString()!=null&&request.getQueryString().length()>0) {
                    qs = "?"+request.getQueryString();
                }
                loadOnOk = base+pi+qs;
                response.setHeader("Refresh", sec+"; "+loadOnOk);
            } else {
                loadOnOk = base + "?sorryPost=1";
            }
            response.setContentType("text/html; charset=utf-8");
            PrintWriter out = response.getWriter();
            out.println("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\" \"http://www.w3.org/TR/html4/loose.dtd\"><html><head>");
            out.println("<title>"+sysmsg("startup_title")+"</title>");
            SurveyAjax.includeAjaxScript(request, response, SurveyAjax.AjaxType.STATUS);
	    if(isUnofficial) {
            	out.println("<script type=\"text/javascript\">timerSpeed = 2500;</script>");
            } else {
                out.println("<script type=\"text/javascript\">timerSpeed = 10000;</script>");
	    }
            out.println("<link rel='stylesheet' type='text/css' href='"+base+"/../surveytool.css'>");
            // todo: include st_top.jsp instead
            out.println("</head><body>");
            if(isUnofficial) {
                out.print("<div class='topnotices'><p class='unofficial' title='Not an official SurveyTool' >");
                out.print("Unofficial");
                out.println("</p></div>");
            }
            if(isBusted != null) {
                out.println(SHOWHIDE_SCRIPT);
                out.println("<script type=\"text/javascript\">clickContinue = '"+loadOnOk+"';</script>");
                out.println("</head>");
                out.println("<body>");
                out.print("<div id='st_err'><!-- for ajax errs --></div><span id='progress'>"+getTopBox()+"</span>");
//                showSpecialHeader(out);
//                out.println("<h1>The CLDR Survey Tool is offline</h1>");
//                out.println("<div class='ferrbox'><pre>" + isBusted +"</pre><hr>");
//                out.println("\n");
//                out.println(getShortened((SurveyForum.HTMLSafe(isBustedStack).replaceAll("\t", "&nbsp;&nbsp;&nbsp;").replaceAll("\n", "<br>"))));
//                out.println("</div><br>");


                out.println("<hr>");
//                if(!isUnofficial) {
//                    out.println("Please try this link for info: <a href='"+CLDR_WIKI_BASE+"?SurveyTool/Status'>"+CLDR_WIKI_BASE+"?SurveyTool/Status</a><br>");
//                    out.println("<hr>");
//                }
                out.println("An Administrator must intervene to bring the Survey Tool back online. <br> " +
                            " <i>This message has been viewed " + pages + " time(s), SurveyTool has been down for " + isBustedTimer + "</i>");
            } else {
            out.print(sysmsg("startup_header"));

            out.print("<div id='st_err'><!-- for ajax errs --></div><span id='progress'>"+getTopBox()+"</span>");

//            String threadInfo = startupThread.htmlStatus();
//            if(threadInfo!=null) {
//            	out.println("<b>Processing:"+threadInfo+"</b><br>");
//            }
//            if(progressWhat != null) {
//                out.println(getProgress()+"<br><hr><br>");
//            }
            
            out.print(sysmsg("startup_wait"));
        }
            out.println("<br><i id='uptime'> "+getGuestsAndUsers()+"</i><br>");
            // TODO: on up, goto <base>
            
            out.println("<script type=\"text/javascript\">loadOnOk = '"+loadOnOk+"';</script>");
            out.println("<script type=\"text/javascript\">clickContinue = '"+loadOnOk+"';</script>");
            if(!isGET) {
                out.println("(Sorry,  we can't automatically retry your "+request.getMethod()+" request - you may attempt Reload in a few seconds "+
                            "<a href='"+base+"'>or click here</a><br>");
            } else {
                out.println("If this page does not load in "+sec+" seconds, you may <a href='"+base+"'>click here to go to the main Survey Tool page</a>");
            }
            out.print(sysmsg("startup_footer"));
            if(!SurveyMain.isUnofficial) {
            	out.println(ShowData.ANALYTICS);
            }
            out.print("</body></html>");
            return false;
        } else {
            return true;
        }
    }
    
    private static String getHome() {
    	if(cldrHome == null) {
	    	String props[] = { 
	    			"catalina.home",
	    			"websphere.home",
	    			"user.dir"
	    	};
	    	for(String prop : props) {
	    		if(cldrHome == null) {
	    			cldrHome = System.getProperty(prop);
	    			if(cldrHome!=null) {
	    				SurveyLog.logger.warning(" Using " + prop + " = " + cldrHome);
	    			} else {
	    				SurveyLog.logger.warning(" Unset: " + prop);
	    			}
	    		}
	    	}
	        if(cldrHome == null) {  
	            busted("Could not find cldrHome. please set catalina.home, user.dir, etc, or set a servlet parameter cldr.home");
//	            for(Object qq : System.getProperties().keySet()) {
//	            	SurveyLog.logger.warning("  >> "+qq+"="+System.getProperties().get(qq));
//	            }
	        } 
    	}
    	return cldrHome;
    }

    /** SQL Console
    */
    private void doSql(WebContext ctx)
    {
        printHeader(ctx, "SQL Console@"+localhost());
        ctx.println("<script type=\"text/javascript\">timerSpeed = 6000;</script>");
        String q = ctx.field("q");
        boolean tblsel = false;        
        printAdminMenu(ctx, "/AdminSql");
        ctx.println("<h1>SQL Console</h1>");
        
        if((dbUtils.dbDir == null) || (isBusted != null)) { // This may or may not work. Survey Tool is busted, can we attempt to get in via SQL?
            ctx.println("<h4>ST busted, attempting to make SQL available via " + cldrHome + "</h4>");
            ctx.println("<pre>");
            specialMessage = "<b>SurveyTool is in an administrative mode- please log off.</b>";
            try {
                if(cldrHome == null) {
                	getHome();
                    File homeFile = new File(cldrHome, "cldr");
                    
                    if(!homeFile.exists()) {
                        throw new InternalError("CLDR basic does not exist- delete parent and start over.");
//                        createBasicCldr(homeFile); // attempt to create
                    }
                    
                    if(!homeFile.exists()) {
                        busted("$(catalina.home)/cldr isn't working as a CLDR home. Not a directory: " + homeFile.getAbsolutePath());
                        return;
                    }
                    cldrHome = homeFile.getAbsolutePath();
                }
                ctx.println("home: " + cldrHome);
                doStartupDB();
            } catch(Throwable t) {
            	SurveyLog.logException(t, ctx);
                ctx.println("Caught: " + t.toString()+"\n");
            }
            ctx.println("</pre>");
        }
        
        if(q.length() == 0) {
            q = DBUtils.DB_SQL_ALLTABLES;
            tblsel = true;
        } else {
            ctx.println("<a href='" + ctx.base() + "?sql=" + vap + "'>[List of Tables]</a>");
        }
        ctx.println("<form method=POST action='" + ctx.base() + "'>");
        ctx.println("<input type=hidden name=sql value='" + vap + "'>");
        ctx.println("SQL: <input class='inputbox' name=q size=80 cols=80 value=\"" + q + "\"><br>");
        ctx.println("<label style='border: 1px'><input type=checkbox name=unltd>Show all?</label> ");
        ctx.println("<label style='border: 1px'><input type=checkbox name=isUpdate>U/I/D?</label> ");
//        ctx.println("<label style='border: 1px'><input type=checkbox name=isUser>UserDB?</label> ");
        ctx.println("<input type=submit name=do value=Query>");
        ctx.println("</form>");

        if(q.length()>0) {
            SurveyLog.logger.severe("Raw SQL: " + q);
            ctx.println("<hr>");
            ctx.println("query: <tt>" + q + "</tt><br><br>");
            Connection conn = null;
            Statement s = null;
            try {
                int i,j;
                
                com.ibm.icu.dev.test.util.ElapsedTimer et = new com.ibm.icu.dev.test.util.ElapsedTimer();
                
//                if(ctx.field("isUser").length()>0) {
//                    conn = getU_DBConnection();
//                } else {
                    conn = dbUtils.getDBConnection();
//                }
                s = conn.createStatement();
                //s.setQueryTimeout(120); // 2 minute timeout. Not supported by derby..
                if(ctx.field("isUpdate").length()>0) {
                    int rc = s.executeUpdate(q);
                    conn.commit();
                    ctx.println("<br>Result: " + rc + " row(s) affected.<br>");
                } else {
                    ResultSet rs = s.executeQuery(q); 
                    conn.commit();
                    
                    ResultSetMetaData rsm = rs.getMetaData();
                    int cc = rsm.getColumnCount();
                    
                    ctx.println("<table summary='SQL Results' class='sqlbox' border='2'><tr><th>#</th>");
                    for(i=1;i<=cc;i++) {
                        ctx.println("<th>"+rsm.getColumnName(i)+ "<br>");
                        int t = rsm.getColumnType(i);
                        switch(t) {
                            case java.sql.Types.VARCHAR: ctx.println("VARCHAR"); break;
                            case java.sql.Types.INTEGER: ctx.println("INTEGER"); break;
                            case java.sql.Types.BLOB: ctx.println("BLOB"); break;
                            case java.sql.Types.TIMESTAMP: ctx.println("TIMESTAMP"); break;
                            case java.sql.Types.BINARY: ctx.println("BINARY"); break;
                            case java.sql.Types.LONGVARBINARY: ctx.println("LONGVARBINARY"); break;
                            default: ctx.println("type#"+t); break;
                        }
                        ctx.println("("+rsm.getColumnDisplaySize(i)+")");
                        ctx.println("</th>");
                    }
                    if(tblsel) {
                        ctx.println("<th>Info</th><th>Rows</th>");
                    }
                    ctx.println("</tr>");
                    int limit = 30;
                    if(ctx.field("unltd").length()>0) {
                        limit = 9999999;
                    }
                    for(j=0;rs.next()&&(j<limit);j++) {
                        ctx.println("<tr class='r"+(j%2)+"'><th>" + j + "</th>");
                        for(i=1;i<=cc;i++) {
                            String v;
                            try {
                                v = rs.getString(i);
                            } catch(SQLException se) {
                                if(se.getSQLState().equals("S1009")) {
                                    v="0000-00-00 00:00:00";
                                } else {
                                    v = "(Err:"+DBUtils.unchainSqlException(se)+")";
                                }
                            } catch (Throwable t) {
                                t.printStackTrace();
                                v = "(Err:"+t.toString()+")";
                            }
                            if(v != null) {
                                ctx.println("<td>"  );
                                if(rsm.getColumnType(i)==java.sql.Types.LONGVARBINARY) {
                                    String uni = DBUtils.getStringUTF8(rs, i);
                                    ctx.println(uni+"<br>");
                                    byte bytes[] = rs.getBytes(i);
                                    for(byte b : bytes) {
                                        ctx.println(Integer.toHexString(((int)b)&0xFF));
                                    }
//                                } else if(rsm.getColumnType(i)==java.sql.Types.TIMESTAMP) {
//                                    String out="(unknown)";
//                                    try {
//                                        out=v.t
//                                    }
                                } else {
                                    ctx.println(v );
//                                  ctx.println("<br>"+rsm.getColumnTypeName(i));
                                }
                                ctx.print("</td>");
                                if(tblsel == true) {
                                    ctx.println("<td>");
                                    ctx.println("<form method=POST action='" + ctx.base() + "'>");
                                    ctx.println("<input type=hidden name=sql value='" + vap + "'>");
                                    ctx.println("<input type=hidden name=q value='" + "select * from "+v+" where 1 = 0'>");
                                    ctx.println("<input type=image src='"+ctx.context("zoom"+".png")+"' value='Info'></form>");
                                    ctx.println("</td><td>");
                                    int count = DBUtils.sqlCount(ctx, conn, "select COUNT(*) from " + v);
                                    ctx.println(count+"</td>");
                                }
                            } else {
                                ctx.println("<td style='background-color: gray'></td>");
                            }
                        }
                        ctx.println("</tr>");
                    }

                    ctx.println("</table>");
                    rs.close();
                }
                
                ctx.println("elapsed time: " + et + "<br>");
            } catch(SQLException se) {
            	SurveyLog.logException(se, ctx);
                String complaint = "SQL err: " + DBUtils.unchainSqlException(se);
                
                ctx.println("<pre class='ferrbox'>" + complaint + "</pre>" );
                SurveyLog.logger.severe(complaint);
            } catch(Throwable t) {
            	SurveyLog.logException(t, ctx);
                String complaint = t.toString();
				t.printStackTrace();
                ctx.println("<pre class='ferrbox'>" + complaint + "</pre>" );
                SurveyLog.logger.severe("Err in SQL execute: " + complaint);
            } finally {
                try {
                    s.close();
                } catch(SQLException se) {
                	SurveyLog.logException(se, ctx);
                    String complaint = "in s.closing: SQL err: " + DBUtils.unchainSqlException(se);
                    
                    ctx.println("<pre class='ferrbox'> " + complaint + "</pre>" );
                    SurveyLog.logger.severe(complaint);
                } catch(Throwable t) {
                	SurveyLog.logException(t, ctx);
                    String complaint = t.toString();
                    ctx.println("<pre class='ferrbox'> " + complaint + "</pre>" );
                    SurveyLog.logger.severe("Err in SQL close: " + complaint);
                }
                DBUtils.closeDBConnection(conn);
            }
        }
        printFooter(ctx);
    }
    
    /**
     * @return memory statistics as a string
     */
    public static String freeMem() {
        Runtime r = Runtime.getRuntime();
        double total = r.totalMemory();
        total = total / 1024000.0;
        double free = r.freeMemory();
        free = free / 1024000.0;
        double used = total-free;
        return "Free memory: " + (int)free + "M / Used: " + (int)used+"M /: total: " + total + "M";
    }
    
    private static final void freeMem(int pages, int xpages) {
        SurveyLog.logger.warning("pages: " + pages+"+"+xpages + ", "+freeMem()+".<br/>");
    }
	
    /** Hash of twiddlable (toggleable) parameters
     * 
     */
    Hashtable twidHash = new Hashtable();

    boolean showToggleTwid(WebContext ctx, String pref, String what) {
		String qKey = "twidb_"+pref;
		String nVal = ctx.field(qKey);
		if(nVal.length()>0) {
			twidPut(pref,new Boolean(nVal).booleanValue());
			ctx.println("<div style='float: right;'><b class='disputed'>changed</b></div>");
		}
        boolean val = twidGetBool(pref, false);
        WebContext nuCtx = (WebContext)ctx.clone();
        nuCtx.addQuery(qKey, new Boolean(!val).toString());
//        nuCtx.println("<div class='pager' style='float: right;'>");
        nuCtx.println("<a href='" + nuCtx.url() + "'>" + what + ": "+
            ((val)?"<span class='selected'>TRUE</span>":"<span class='notselected'>false</span>") + "</a><br>");
//        nuCtx.println("</div>");
        return val;
    }
	
	private boolean twidGetBool(String key, boolean defVal) {
        Boolean b = (Boolean)twidHash.get(key);
        if(b == null) {
            return defVal;
        } else {
            return b.booleanValue();
        }
	}

	private boolean twidGetBool(String key) {
        return twidGetBool(key,false);
	}
	
	public void twidPut(String key, boolean val) {
		twidHash.put(key, new Boolean(val));
	}
	

	/* twiddle: these are params settable at runtime. */
    boolean twidBool(String x) {
        return twidBool(x,false);
    }
	
    synchronized boolean twidBool(String x, boolean defVal)
    {
        boolean ret = twidGetBool(x, defVal);
        twidPut(x, ret);
        return ret;
    }
    
    /**
     * Admin panel
     * @param ctx
     * @param helpLink
     */
	void printAdminMenu(WebContext ctx, String helpLink) {
    
        boolean isDump = ctx.hasField("dump");
        boolean isSql = ctx.hasField("sql");
    
        ctx.print("<div style='float: right'><a class='notselected' href='" + ctx.base() + "'><b>[SurveyTool main]</b></a> | ");
        ctx.print("<a class='notselected' href='" + ctx.base() + "?letmein="+vap+"&amp;email=admin@'><b>Login as admin@</b></a> | ");
        ctx.print("<a class='"+(isDump?"":"not")+"selected' href='" + ctx.base() + "?dump="+vap+"'>Admin</a>");
        ctx.print(" | ");
        ctx.print("<a class='"+(isSql?"":"not")+"selected' href='" + ctx.base() + "?sql="+vap+"'>SQL</a>");
        ctx.print("<br>");
        ctx.printHelpLink(helpLink, "Admin Help", true);
        ctx.println("</div>");
    }
    
    private void doAdminPanel(WebContext ctx)
    {
        String action = ctx.field("action");
        printHeader(ctx, "Admin@"+localhost() + " | " + action);
        ctx.println("<script type=\"text/javascript\">timerSpeed = 6000;</script>");
        printAdminMenu(ctx, "/AdminDump");
        ctx.println("<h1>SurveyTool Administration</h1>");
        ctx.println("<hr>");

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        
        long deadlockedThreads[] = threadBean.findDeadlockedThreads();

        
        if(action.equals("")) {
            action = "sessions";
        }
        WebContext actionCtx = (WebContext)ctx.clone();
        actionCtx.addQuery("dump",vap);
		WebContext actionSubCtx = (WebContext)actionCtx.clone();
		actionSubCtx.addQuery("action",action);

		actionCtx.println("Click here to update data: ");
		printMenuButton(actionCtx, action, "upd_1", "Easy Data Update", "action", "Update:");       
		actionCtx.println(" <br> ");
		printMenu(actionCtx, action, "sessions", "User Sessions", "action");    
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "stats", "Internal Statistics", "action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "tasks", "Tasks and Threads"+
									((deadlockedThreads!=null)?actionCtx.iconHtml("warn","deadlock"):
															  actionCtx.iconHtml("okay","no deadlock")),
														"action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "statics", "Static Data", "action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "specialusers", "Specialusers", "action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "specialmsg", "Update Header Message", "action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "upd_src", "Manage Sources", "action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "load_all", "Load all locales", "action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "add_locale", "Add a locale", "action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "bulk_submit", "Bulk Data Submit", "action");       
		actionCtx.println(" | ");
		printMenu(actionCtx, action, "srl", "Dangerous Options...", "action");  // Dangerous items

        if(action.startsWith("srl")) {
            ctx.println("<br><ul><div class='ferrbox'>");
            if(action.equals("srl")) {
                ctx.println("<b>These menu items are dangerous and may have side effects just by clicking on them.</b><br>");
            }
            actionCtx.println(" | ");
            printMenu(actionCtx, action, "srl_vet_imp", "Update Implied Vetting", "action");       
            actionCtx.println(" | ");
            printMenu(actionCtx, action, "srl_vet_res", "Update Results Vetting", "action");       
            actionCtx.println(" | ");
            printMenu(actionCtx, action, "srl_vet_sta", "Update Vetting Status", "action");       
            actionCtx.println(" | ");
            printMenu(actionCtx, action, "srl_vet_nag", "MAIL: send out vetting reminder", "action");       
            actionCtx.println(" | ");
/*            printMenu(actionCtx, action, "srl_vet_upd", "MAIL: vote change [daily]", "action");       
            actionCtx.println(" | "); */
            /*
            printMenu(actionCtx, action, "srl_db_update", "Update <tt>base_xpath</tt>", "action");       
            actionCtx.println(" | ");
            */
            printMenu(actionCtx, action, "srl_vet_wash", "Clear out old votes", "action");       
            actionCtx.println(" | ");
            printMenu(actionCtx, action, "srl_output", "Output Vetting Data", "action");       
            actionCtx.println(" | ");
            printMenu(actionCtx, action, "srl_crash", "Bust Survey Tool", "action");       
            actionCtx.println(" | ");
            
            
            printMenu(actionCtx, action, "srl_twiddle", "twiddle params", "action");       
            ctx.println("</div></ul>");
        }
        actionCtx.println("<br>");
        
		/* Begin sub pages */
		
        if(action.equals("stats")) {
        	ctx.println("<div class='pager'>");
        	ctx.println("DB version " + dbUtils.dbInfo+ ",  ICU " + com.ibm.icu.util.VersionInfo.ICU_VERSION+
        			", Container: " + config.getServletContext().getServerInfo()+"<br>");
        	ctx.println(uptime + ", " + pages + " pages and "+xpages+" xml pages served.<br/>");
        	//        r.gc();
        	//        ctx.println("Ran gc();<br/>");

        	ctx.println("String hash has " + stringHash.size() + " items.<br/>");
        	ctx.println("xString hash info: " + xpt.statistics() +"<br>");
        	if(gBaselineHash != null) {
        		ctx.println("baselinecache info: " + (gBaselineHash.size()) + " items."  +"<br>");
        	}
        	ctx.println("CLDRFile.distinguishedXPathStats(): " + CLDRFile.distinguishedXPathStats() + "<br>");
        	
        	try {
				getDBSourceFactory().stats(ctx).append("<br>");
	        	dbUtils.stats(ctx).append("<br>");
			} catch (IOException e) {
	        	SurveyLog.logException(e, ctx);
				ctx.println("Error " + e + " loading other stats<br/>");
				e.printStackTrace();
				
			}
        	ctx.println("Open user files: " + allUserLocaleStuffs.size()+"<br/>");
        	ctx.println("</div>");

        	StringBuffer buf = new StringBuffer();
        	ctx.println("<h4>Memory</h4>");

        	appendMemoryInfo(buf, false);

        	ctx.print(buf.toString());
        	buf.delete(0, buf.length());

        	ctx.println("<a class='notselected' href='" + ctx.jspLink("about.jsp") +"'>More version information...</a><br/>");

        } else if(action.equals("statics")) {
            ctx.println("<h1>Statics</h1>");
            ctx.staticInfo();
        } else if(action.equals("tasks")) {
            WebContext subCtx = (WebContext)ctx.clone();
            actionCtx.addQuery("action",action);


	    if(isUnofficial) {
			ctx.println("<form style='float: right;' method='POST' action='"+actionCtx.url()+"'>");
			actionCtx.printUrlAsHiddenFields();
			ctx.println("<input type=submit value='Do Nothing For Ten Seconds' name='10s'></form>");
			
			ctx.println("<form style='float: right;' method='POST' action='"+actionCtx.url()+"'>");
			actionCtx.printUrlAsHiddenFields();
			ctx.println("<input type=submit value='Do Nothing For Ten Minutes' name='10m'></form>");
			
			ctx.println("<form style='float: right;' method='POST' action='"+actionCtx.url()+"'>");
			actionCtx.printUrlAsHiddenFields();
			ctx.println("<input type=submit value='Do Nothing Every Ten Seconds' name='p10s'></form>");
	    }


	    String fullInfo = startupThread.toString();

        ctx.println("<h1 title='"+fullInfo+"'>Tasks</h1>");
	    
	    if(!startupThread.mainThreadRunning()) {	    
		ctx.println("<i>Main thread is not running.</i><br>");
	    }

            SurveyThread.SurveyTask acurrent = startupThread.current;

            if(acurrent!=null) {
		ctx.println("<hr>");
		ctx.println("<table border=0><tr><th>Active Task:</th>");
		ctx.println("<td>"+acurrent.toString()+"</td>");
		
		ctx.println("<td><a href='#currentTask'>"+ctx.iconHtml("zoom","Zoom in on task..")+"</a></td>");

		ctx.println("<td>");
		ctx.println("<form method='POST' action='"+actionCtx.url()+"'>");
		actionCtx.printUrlAsHiddenFields();
		ctx.println("<input type=submit value='Stop Active Task' name='tstop'></form>");
		ctx.println("<form method='POST' action='"+actionCtx.url()+"'>");
		actionCtx.printUrlAsHiddenFields();
		ctx.println("<input type=submit value='Kill Active Task' name='tkill'></form>");
		ctx.println("</td></tr></table>");
            }
	    if(startupThread.tasksRemaining()>1) {
		ctx.println("<i>"+startupThread.tasksRemaining()+" total tasks remaining.</i><br>");
	    }
            
           
	    if(ctx.hasField("10s")) {
	    	startupThread.addTask(new SurveyTask("Waste 10 Seconds")
	    	{
	    		public void run() throws Throwable {
	    			CLDRProgressTask task = this.openProgress("Waste 10 Seconds",10);
	    			try {
	    				for(int i=0;i<10;i++) {
	    					task.update(i);
		    				Thread.sleep(1000);
	    				}
	    			} finally {
	    				task.close();
	    			}
	    		}
	    	});
	    	ctx.println("10s task added.\n");
	    } else if(ctx.hasField("10m")) {
		    	startupThread.addTask(new SurveyTask("Waste 10 Minutes")
		    	{
		    		public void run() throws Throwable {
		    			CLDRProgressTask task = this.openProgress("Waste 10 Minutes",10);
		    			try {
		    				for(int i=0;i<10;i++) {
		    					task.update(i);
			    				Thread.sleep(1000*60);
		    				}
		    			} finally {
		    				task.close();
		    			}
		    		}
		    	});
		    	ctx.println("10m task added.\n");
//	    } else if(ctx.hasField("p10s")) {
//	    	addPeriodicTask(new TimerTask()
//	    	{
//	    		@Override
//	    		public void run() throws Throwable {
//	    			CLDRProgressTask task = openProgress("P:Waste 3 Seconds",10);
//	    			try {
//	    				for(int i=0;i<3;i++) {
//	    					task.update(i);
//		    				Thread.sleep(1000);
//	    				}
//	    			} finally {
//	    				task.close();
//	    			}
//	    		}
//	    	});
//	    	ctx.println("p10s3s task added.\n");
	    } else  if(ctx.hasField("tstop")) {
            	if(acurrent!=null) {
		    acurrent.stop();
		    ctx.println(acurrent + " stopped");
            	}
            } else if(ctx.hasField("tkill")) {
            	if(acurrent!=null) {
		    acurrent.kill();
		    ctx.println(acurrent + " killed");
            	}
            }
            
            ctx.println("<hr>");
            ctx.println("<h2>Threads</h2>");
            Map<Thread, StackTraceElement[]> s = Thread.getAllStackTraces();
            ctx.print("<ul id='threadList'>");
            
            Set<Thread> threadSet = new TreeSet<Thread>(new Comparator<Thread>() {
				@Override
				public int compare(Thread o1, Thread o2) {
					int rc = 0;
					rc = o1.getState().compareTo(o2.getState());
					if(rc==0) {
						rc = o1.getName().compareTo(o2.getName());
					}
					return rc;
				}
			});
            threadSet.addAll(s.keySet());
            
            for(Thread t : threadSet) {
            	ctx.println("<li class='"+t.getState().toString()+"'><a href='#"+t.getId()+"'>"+t.getName()+"</a>  - "+t.getState().toString());
            	ctx.println("</li>");
            }
            ctx.println("</ul>");
           	// detect deadlocks
            if(deadlockedThreads != null) {
            	ctx.println("<h2>"+ctx.iconHtml("stop", "deadlocks")+" deadlocks</h2>");
            	
            	ThreadInfo deadThreadInfo[] = threadBean.getThreadInfo(deadlockedThreads, true, true);
            	for(ThreadInfo deadThread : deadThreadInfo) {
            		ctx.println("<b>Name: " + deadThread.getThreadName()+" / #"+deadThread.getThreadId()+"</b><br>");
            		ctx.println("<pre>"+deadThread.toString()+"</pre>");
            	}
            } else {
            	ctx.println("<i>no deadlocked threads</i>");
            }
            
            
            // show all threads
            for(Thread t : threadSet) {
		    if(t == startupThread) { 
			ctx.println("<a name='currentTask'></a>");
		    }
		    ctx.println("<a name='"+t.getId()+"'><h3>"+t.getName()+"</h3></a> - "+t.getState().toString());
            	
            	if(t.getName().indexOf(" ST ")>0) {
                    ctx.println("<form method='POST' action='"+actionCtx.url()+"'>");
                    actionCtx.printUrlAsHiddenFields();
                    ctx.println("<input type=hidden name=killtid value='"+t.getId()+"'>");
                    ctx.println("<input type=submit value='Kill Thread #"+t.getId()+"'></form>");
                    
                    if(ctx.fieldLong("killtid") == (t.getId())) {
                    	ctx.println(" <br>(interrupt and stop called..)<br>\n");
                    	try {
                    		t.interrupt();
                    		t.stop(new InternalError("Admin wants you to stop"));
                    	} catch(Throwable tt) {
                        	SurveyLog.logException(tt, ctx);
                    		ctx.println("[caught exception " + tt.toString()+"]<br>");
                    	}
                    }
                    
            	}
            	
            	
            	StackTraceElement[] elem = s.get(t);
            	ctx.print("<pre>");
            	for(StackTraceElement el : elem) {
            		ctx.println(el.toString());
            	}
            	ctx.print("</pre>");
            }
            
        } else if(action.equals("sessions"))  {
            ctx.println("<h1>Current Sessions</h1>");
            ctx.println("<table class='list' summary='User list'><tr class='heading'><th>age</th><th>user</th><th>what</th><th>action</th></tr>");
            int rowc = 0;
            for(Iterator li = CookieSession.getAll();li.hasNext();) {
                CookieSession cs = (CookieSession)li.next();
                ctx.println("<tr class='row"+(rowc++)%2+"'><!-- <td><tt style='font-size: 72%'>" + cs.id + "</tt></td> -->");
                ctx.println("<td>" + timeDiff(cs.last) + "</td>");
                if(cs.user != null) {
                    ctx.println("<td><tt>" + cs.user.email + "</tt><br/>" + 
                                "<b>"+cs.user.name + "</b><br/>" + 
                                cs.user.org + "</td>");
                } else {
                    ctx.println("<td><i>Guest</i><br><tt>"+cs.ip+"<tt></td>");
                }
                ctx.println("<td>");
                Hashtable lh = cs.getLocales();
                Enumeration e = lh.keys();
                if(e.hasMoreElements()) { 
                    for(;e.hasMoreElements();) {
                        String k = e.nextElement().toString();
                        ctx.println(new ULocale(k).getDisplayName(ctx.displayLocale) + " ");
                    }
                }
                ctx.println("</td>");
                
                ctx.println("<td>");
                printLiveUserMenu(ctx, cs);
                if(cs.id.equals(ctx.field("unlink"))) {
                    cs.remove();
                    ctx.println("<br><b>Removed.</b>");
                }
                ctx.println(  " | <a class='notselected' href='"+actionCtx.url()+"&amp;banip="+URLEncoder.encode(cs.ip)+"'>Ban</a>");
                if(cs.ip.equals(ctx.field("banip"))) {
                    ctx.println("<b> Banned:</b> " + cs.banIn(BAD_IPS) + "<hr/> and Kicked.");
                    cs.remove();
                }
                ctx.println("</td>");
                
                ctx.println("</tr>");
                
                if(cs.id.equals(ctx.field("see"))) {
                    ctx.println("<tr><td colspan=5>");
                    ctx.println("Stuff: " + cs.toString() + "<br>");
                    ctx.staticInfo_Object(cs.stuff);
                    ctx.println("<hr>Prefs: <br>");
                    ctx.staticInfo_Object(cs.prefs);
                    ctx.println("</td></tr>");
                }
            }
            ctx.println("</table>");
            String rmip = ctx.field("rmip");
            if(rmip !=null && !rmip.isEmpty()) {
                BAD_IPS.remove(rmip);
                ctx.println("<b>Removed bad IP:"+rmip+"</b>");
            }
            if(!BAD_IPS.isEmpty()) {
                ctx.println("<h3>Bad IPs</h3>");
                ctx.println("<table class='list'><tr class='heading'><th>IP</th><th>Info</th><th>(delete)</th></tr>");
                rowc=0;
                for(Entry<String, Object> e : BAD_IPS.entrySet()) {
                    ctx.println("<tr class='row"+(rowc++)%2+"'>");
                    ctx.println( "<th>"+e.getKey() + "</th>");
                    ctx.println( "<td>" + e.getValue() + "</td>");
                    ctx.println(  "<td><a class='notselected' href='"+actionCtx.url()+"&amp;rmip="+URLEncoder.encode(e.getKey())+"'>(remove)</td>");
                    ctx.println("</tr>");
                }
                ctx.println("</table>");
            }
        } else if(action.equals("upd_1")) {
            WebContext subCtx = (WebContext)ctx.clone();
            actionCtx.addQuery("action",action);
            ctx.println("<h2>1-click vetting update</h2>");
            // 1: update all sources

            try {
                final WebContext fakeContext = new WebContext(true);
                fakeContext.sm = this;

                startupThread.addTask(new SurveyThread.SurveyTask("Updating All Data") {
                    public void run() throws Throwable {
                        CLDRProgressTask progress = openProgress("Data Update");
                        try {
                            String baseName = this.name;
                            int cnt = 0;
                            progress.update(" reset caches and update all");
                            try {
                                //                CLDRDBSource mySrc = makeDBSource(conn, null, CLDRLocale.ROOT);
                                resetLocaleCaches();
                                SurveyLog.logger.info("Update count: " + getDBSourceFactory().manageSourceUpdates(fakeContext, fakeContext.sm, true)); // do a quiet 'update all'
                            } finally {
                                //                SurveyMain.closeDBConnection(conn);
                            }
                            // 2: load all locales
                            progress.update("load all locales");
                            loadAllLocales(fakeContext, this);
                            // 3: update impl votes
                            progress.update("Update implied votes");
                            ElapsedTimer et = new ElapsedTimer();
                            int n = vet.updateImpliedVotes();
                            //ctx.println("Done updating "+n+" implied votes in: " + et + "<br>");
                            // 4: UpdateAll
                            //ctx.println("<h4>Update All</h4>");
                            progress.update("Update All");

                            et = new ElapsedTimer();
                            n = vet.updateResults(false); // don't RE update.
                            //ctx.println("Done updating "+n+" vote results in: " + et + "<br>");
                            progress.update(" Invalidate ROOT");
                            lcr.invalidateLocale(CLDRLocale.ROOT);
                            // 5: update status
                            progress.update(" Update Status");
                            et = new ElapsedTimer();
                            n = vet.updateStatus();
                            SurveyLog.logger.warning("Done updating "+n+" statuses [locales] in: " + et + "<br>");
                            SurveyMain.specialHeader = "Data update done! Please log off. Administrator: Please restart your Survey Tool.";
                        } finally {
                            progress.close();
                        }
                    }
                });
            } catch (IOException e) {
            	SurveyLog.logException(e, ctx);
                // TODO Auto-generated catch block
                e.printStackTrace();
                throw new InternalError("Couldn't create fakeContext for administration.");
            }
        } else if(action.equals("bulk_submit")) {
            WebContext subCtx = (WebContext)ctx.clone();
            actionCtx.addQuery("action",action);

            String aver=newVersion; // TODO: should be from 'new_version'
            ctx.println("<h2>Bulk Data Submission Updating for "+aver+"</h2><br/>\n");

            Set<UserRegistry.User> updUsers = new HashSet<UserRegistry.User>();

            
            if(true) { ctx.println("Nope, not until you fix http://unicode.org/cldr/trac/ticket/3656#comment:3 in this code."); return; }

            // from config 
            String bulkStrOrig = survprops.getProperty(CLDR_BULK_DIR,"");
            // from query if there, else config
            if(ctx.hasField(CLDR_BULK_DIR)) {
                bulkStr = ctx.field(CLDR_BULK_DIR);
            }
            if(bulkStr == null || bulkStr.length()==0) {
                bulkStr = bulkStrOrig;
            }
            File bulkDir = null;
            if(bulkStr!=null&&bulkStr.length()>0) {
                bulkDir = new File(bulkStr);
            }
            // dir change form
            ctx.println("<form method='POST' action='"+actionCtx.url()+"'>");
            actionCtx.printUrlAsHiddenFields();
            ctx.println("<label>Bulk Dir: " +
                    "<input name='"+CLDR_BULK_DIR+"' value='"+bulkStr+"' size='60'>" +
                    "</label> <input type=submit value='Set'><br>" +
            "</form><hr/>");
            if(bulkDir==null||!bulkDir.exists()||!bulkDir.isDirectory()) {
                ctx.println(ctx.iconHtml("stop","could not load bulk data")+"The bulk data dir "+CLDR_BULK_DIR+"="+bulkStr+" either doesn't exist or isn't set in cldr.properties. (Server requires reboot for this parameter to take effect)</i>");
            } else try {

                ctx.println("<h3>Bulk dir: "+bulkDir.getAbsolutePath()+"</h3>");
                boolean doimpbulk = ctx.hasField("doimpbulk");
                boolean istrial = ctx.hasField("istrial");
                ctx.println("<form method='POST' action='"+actionCtx.url()+"'>");
                actionCtx.printUrlAsHiddenFields();
                ctx.println("<input type=submit value='Accept all implied votes' name='doimpbulk'></form>");
                ctx.println("<form method='POST' action='"+actionCtx.url()+"'>");
                actionCtx.printUrlAsHiddenFields();
                ctx.println("<input type=submit value='Do a trial run' name='istrial'></form>");
                if(istrial) {
                    ctx.print("<i>trial run. press the button to accept these votes.</i>");
                } else if(doimpbulk) {
                    ctx.print("<i>Real run.</i>");
                } else {
                    ctx.print("Press one of the buttons to begin.");
                }

                Set<File> files = new TreeSet<File>(Arrays.asList(getInFiles(bulkDir)));

                ctx.print("Jump to: ");
                for(File file : files) {
                    ctx.print("<a href=\"#"+getLocaleOf(file)+"\">"+file.getName()+"</a> ");
                }
                ctx.println("<br>");

                Set<CLDRLocale> toUpdate = new HashSet<CLDRLocale>();
                int wouldhit=0;
                
                if(!istrial && !doimpbulk) return;

                CLDRProgressTask progress = openProgress("bulk data import", files.size());
                int nn=0;
                try { for(File file : files ) {
                    /*synchronized(vet) */ {
                        CLDRLocale loc = getLocaleOf(file);
                        DisplayAndInputProcessor processor = new DisplayAndInputProcessor(loc.toULocale());
                        ctx.println("<a name=\""+loc+"\"><h2>"+file.getName()+" - "+loc.getDisplayName(ctx.displayLocale)+"</h2></a>");
                        CLDRFile c = SimpleFactory.makeFile(file.getPath(), loc.getBaseName(), CLDRFile.DraftStatus.unconfirmed);
                        XPathParts xpp = new XPathParts(null,null);

                        OnceWarner warner = new OnceWarner();
                        XMLSource stSource = getDBSourceFactory().getInstance(loc);

                        progress.update(nn++, loc.toString());
                        for(String x : c) {
                            String full = c.getFullXPath(x);
                            String alt = XPathTable.getAlt(full, xpp);
                            String val0 = c.getStringValue(x);
                            Exception exc[] = new Exception[1];
                            String val = processor.processInput(x, val0, exc);
                            if(alt==null||alt.length()==0) {
                                if(!full.startsWith("//ldml/identity")) {
                                    warner.warnOnce(ctx, "countNoAlt", "warn", "Xpath with no 'alt' tag: " + full);
                                }
                                continue;
                            }
                            String altPieces[] = LDMLUtilities.parseAlt(alt);
                            if(altPieces[1]==null) {
                                warner.warnOnce(ctx, "countNoAlt", "warn", "Xpath with no 'alt-proposed' tag: " + full);
                                continue;
                            }
                            /*
	            		if(alt.equals("XXXX")) {
	            			alt = "proposed-u1-implicit1.7";
	            			x = XPathTable.removeAlt(x, xpp);
	            		}*/
                            int n = XPathTable.altProposedToUserid(altPieces[1]);
                            if(n<0) {
                                warner.warnOnce(ctx, "countNoUser", "warn", "Xpath with no userid in 'alt' tag: " + full);
                                continue;
                            }
                            User ui = null;
                            if(n>=0) ui = reg.getInfo(n);
                            if(ui==null) {
                                warner.warnOnce(ctx, "countBadUser", "warn", "Bad userid '"+n+"': " + full);
                                continue;
                            }
                            updUsers.add(ui);
                            String base_xpath = xpt.xpathToBaseXpath(x);
                            int base_xpath_id = xpt.getByXpath(base_xpath);
                            int vet_type[] = new int[1];
                            int j = vet.queryVote(loc, n, base_xpath_id, vet_type);
                            //int dpathId = xpt.getByXpath(xpathStr);
                            // now, find the ID to vote for.
                            Set<String> resultPaths = new HashSet<String>();
                            String baseNoAlt = xpt.removeAlt(base_xpath);
                            if(true) throw new InternalError("Nope, not until you fix http://unicode.org/cldr/trac/ticket/3656#comment:3 in this code.");
                            if(altPieces[0]==null) {
                                stSource.getPathsWithValue(val, base_xpath, resultPaths);
                            } else {
                                Set<String> lotsOfPaths = new HashSet<String>();
                                stSource.getPathsWithValue(val, baseNoAlt, lotsOfPaths);
//                                SurveyLog.logger.warning("pwv["+val+","+baseNoAlt+",)="+lotsOfPaths.size());
                                if(!lotsOfPaths.isEmpty()) {
                                    for(String s : lotsOfPaths) {
                                        String alt2 = XPathTable.getAlt(s, xpp);
                                        if(alt2 != null) {
                                            String altPieces2[] = LDMLUtilities.parseAlt(alt2);
                                            if(altPieces2[0]!=null && altPieces[0].equals(altPieces[0])) {
                                                resultPaths.add(s);
//                                                SurveyLog.logger.warning("==match: " + s);
                                            }
                                        }
                                    }
                                }
                            }

                            String resultPath = null;

                            //                                 if(warner.count("countAdd")==0 && warner.count("countVoteMain")==0) {
                            //                                     ctx.println("<tr><th>");				
                            //                                     ctx.println("<a href='"+ctx.base()+"?_="+loc+"&xpath="+base_xpath_id+"'>");
                            //                                     ctx.println(base_xpath_id+"</a></th>");
                            //                                     ctx.println("<td>" + j+"</td><td>"+val+"</td>");
                            //                                     ctx.println("<td>");
                            //                                 }

                            if(exc[0]!=null) {
                                ctx.println("Exceptions on DAIP: ");
                                for(Exception ex : exc) { 
                                    ctx.print(ex.toString()+" ");
                                    ex.printStackTrace();
                                }
                                ctx.println("<br>");
                            }


                            if(resultPaths.isEmpty()) {
                                warner.warnOnce(ctx, "countAdd", "zoom", "Value must be added", xpt.getPrettyPath(base_xpath)+": " + val );
                                if(!doimpbulk) {
                                    warner.warnOnce(ctx, "countReady", "okay","<i>Ready to update.</i>");
                                    ctx.println("</td></tr>");
                                    continue; // don't try to add.
                                }
                                /* NOW THE FUN PART */
                                for(int i=0;(resultPath==null)&&i<1000;i++) {
                                    String proposed = "proposed-u"+n+"-b"+i;
                                    String newAlt = LDMLUtilities.formatAlt(altPieces[0], proposed);
                                    String newxpath = baseNoAlt+"[@alt=\"" + newAlt + "\"]";
                                    String newoxpath = newxpath+"[@draft=\"unconfirmed\"]";

                                    if(stSource.hasValueAtDPath(newxpath)) continue;

                                    /* Write! */
                                    stSource.putValueAtPath(newoxpath, val);
                                    toUpdate.add(loc);

                                    resultPath = newxpath;
                                    if(warner.count("countAdd")<2) {
                                        Set<String> nresultPaths = new HashSet<String>();
                                        stSource.getPathsWithValue(val, base_xpath, nresultPaths);
                                        ctx.println(">> now have " + nresultPaths.size() + " paths with value: <tt>"+base_xpath+"</tt> <ol>");
                                        for(String res : nresultPaths) { 
                                            ctx.println(" <li>"+res+"</li>\n");
                                        }
                                        ctx.println("</ol>\n");
                                        String nr = stSource.getValueAtDPath(newxpath);
                                        if(nr==null) {
                                            ctx.println("Couldn't get valueatdpath "+ newxpath+"<br>\n");
                                        } else if(nr.equals(val)) {
                                            //	ctx.println("RTT ok with " + newxpath + " !<br>\n");
                                        } else {
                                            ctx.println("RTT not ok!!!!<br>\n");
                                        }
                                    }
                                }


                            } else if(resultPaths.size()>1) {
                                /* ok, more than one result. stay cool.. */
                                /* #1 - look for the base xpath. */
                                for(String path : resultPaths) {
                                    if(path.equals(base_xpath)) {
                                        resultPath = path;
                                        warner.warnOnce(ctx, "countVoteMain", "squo", "Using base xpath for " + base_xpath);
                                    }
                                }
                                /* #2 look for something with a vote */
                                if(resultPath==null) {
                                    String winPath = stSource.getWinningPath(base_xpath);
                                    String winDpath = CLDRFile.getDistinguishingXPath(winPath, null, true);
                                    for(String path : resultPaths) {
                                        String aDPath = CLDRFile.getDistinguishingXPath(path, null, true);	
                                        if(aDPath.equals(winDpath)) {
                                            if(false)  					ctx.println("Using winning dpath " + aDPath +"<br>");
                                            resultPath = aDPath;
                                        }
                                    }
                                }
                                /* #3 just take the first one */
                                if(resultPath==null) {
                                    resultPath = resultPaths.toArray(new String[0])[0];
                                    //ctx.println("Using [0] path " + resultPath);
                                }
                                if(resultPath==null) {
                                    ctx.println(ctx.iconHtml("stop", "more than one result!")+"More than one result!<br>");
                                    if(true) ctx.println(loc+" " + xpt.getPrettyPath(base_xpath) + " / "+ alt + " (#"+n+" - " + ui +")<br/>");
                                    ctx.println("</td></tr>");
                                    continue;
                                }
                            } else{
                                resultPath = resultPaths.toArray(new String[0])[0];
                            }

                            /*temp*/if(resultPath == null) {
                                ctx.println("</td></tr>");
                                continue; 
                            }

                            String xpathStr = CLDRFile.getDistinguishingXPath(resultPath, null, false);
                            int dpathId = xpt.getByXpath(xpathStr);
                            if(false) ctx.println(loc+" " + xpt.getPrettyPath(base_xpath) + " / "+ alt + " (#"+n+" - " + ui +"/" + dpathId+" <br/>");




                            if(dpathId == j) {
                                warner.warnOnce(ctx, "countAlready", "squo", "Vote already correct");
                                //	                    	ctx.println(" "+ctx.iconHtml("squo","current")+" ( == current vote ) <br>");
                                //	                    	  already++;
                            } else {
                                if(j>-1) {
                                    if(vet_type[0]==Vetting.VET_IMPLIED) {
                                        warner.warnOnce(ctx, "countOther", "okay", "Changing existing implied vote");
                                    } else {
                                        warner.warnOnce(ctx, "countExplicit", "warn", "NOT changing existing different vote");
                                        ctx.println("</td></tr>");
                                        continue;
                                    }
                                    //	            			  ctx.println(" "+ctx.iconHtml("warn","already")+"Current vote: "+j+"<br>");
                                    //	            			  different++;
                                }
                                if(doimpbulk) {
                                    vet.vote(loc, base_xpath_id, n, dpathId, Vetting.VET_IMPLIED);
                                    toUpdate.add(loc);
                                    warner.warnOnce(ctx, "countReady", "okay","<i>Updating.</i>");
                                } else {
                                    warner.warnOnce(ctx, "countReady", "okay","<i>Ready to update.</i>");
                                    /*  wouldhit++;
	            				  toUpdate.add(loc);*/
                                }
                            }
                            ctx.println("</td></tr>");

                        } /* end xpath */
                        // 			ctx.println("</table>");
                        ctx.println("<hr>");
                        /*if(already>0 ) {
	            		  ctx.println(" "+ctx.iconHtml("squo","current")+""+already+" items already had the correct vote.<br>");
	            	  }
	            	  if(different>0) {
	            		  ctx.println(" "+ctx.iconHtml("warn","different")+" " + different + " items had a different vote already cast.<br>");
	            	  }
	            	  if(doimpbulk && !toUpdate.isEmpty()) {
	            		  ctx.println("<h3>"+wouldhit+" Locale Updates in " + toUpdate.size() + " locales ..</h3>");
	            		  for(CLDRLocale l : toUpdate) {
	        				  vet.deleteCachedLocaleData(l);
	            			  dbsrcfac.needUpdate(l);
	            			  ctx.print(l+"...");
	            		  }
	            		  ctx.println("<br>");
	            		  int upd = dbsrcfac.update();
	            		  ctx.println(" Updated. "+upd + " deferred updates done.<br>");
	            	  } else if(wouldhit>0) {
	            		  ctx.println("<h3>Ready to update "+wouldhit+" Locale Updates in " + toUpdate.size() + " locales ..</h3>");
	                      ctx.println("<form method='POST' action='"+actionCtx.url()+"'>");
	                      actionCtx.printUrlAsHiddenFields();
	                      ctx.println("<input type=submit value='Accept all implied votes' name='doimpbulk'></form>");
	            	  }*/
                        if(!toUpdate.isEmpty()) {
                            ctx.println("Updating: ");
                            for(CLDRLocale toul : toUpdate) {
                                this.updateLocale(toul);
                                getDBSourceFactory().needUpdate(toul);
                                ctx.println(toul+" ");
                            }
                            ctx.println("<br>");
                            ctx.println("Clearing cache: #"+getDBSourceFactory().update()+"<br>");
                        }
                        toUpdate.clear();

                        warner.summarize(ctx);

                    } /* end sync */
                } /* end outer loop */
                } finally {
                    progress.close();
                }
                ctx.println("<hr>");
                ctx.println("<h3>Users involved:</h3>");
                for(User auser : updUsers) {
                    ctx.println(auser.toString());
                    ctx.println("<br>");
                }
                /*
            } catch (SQLException t) {
                 t.printStackTrace();
                 ctx.println("<b>Err in bulk import </b> <pre>" + unchainSqlException(t)+"</pre>");
                 */
            } catch (Throwable t) {
            	SurveyLog.logException(t, ctx);
                t.printStackTrace();
                ctx.println("Err : " + t.toString() );
            }
        } else if(action.equals("srl")) {
            ctx.println("<h1>"+ctx.iconHtml("warn", "warning")+"Please be careful!</h1>");
        } else if(action.equals("srl_vet_imp")) {
            WebContext subCtx = (WebContext)ctx.clone();
            subCtx.addQuery("dump",vap);
            ctx.println("<br>");
            ElapsedTimer et = new ElapsedTimer();
            int n = vet.updateImpliedVotes();
            ctx.println("Done updating "+n+" implied votes in: " + et + "<br>");
        } else if(action.equals("srl_vet_sta")) {
            ElapsedTimer et = new ElapsedTimer();
            int n = vet.updateStatus();
            ctx.println("Done updating "+n+" statuses [locales] in: " + et + "<br>");
		///} else if(action.equals("srl_dis_nag")) {
		///	vet.doDisputeNag("asdfjkl;", null);
		///	ctx.println("\u3058\u3083\u3001\u3057\u3064\u308c\u3044\u3057\u307e\u3059\u3002<br/>"); // ??
        } else if(action.equals("srl_vet_nag")) {
            if(ctx.field("srl_vet_nag").length()>0) {
                ElapsedTimer et = new ElapsedTimer();
                vet.doNag();
                ctx.println("Done nagging in: " + et + "<br>");
            }else{
                actionCtx.println("<form method='POST' action='"+actionCtx.url()+"'>");
                actionCtx.printUrlAsHiddenFields();
                actionCtx.println("Send Nag Email? <input type='hidden' name='srl_vet_nag' value='Yep'><input type='hidden' name='action' value='srl_vet_nag'><input type='submit' value='Nag'></form>");
            }
//        } else if(action.equals("srl_vet_upd")) {
//            ElapsedTimer et = new ElapsedTimer();
//            int n = vet.updateStatus();
//            ctx.println("Done updating "+n+" statuses [locales] in: " + et + "<br>");
        } else if(action.equals("srl_vet_res")) {
            WebContext subCtx = (WebContext)ctx.clone();
            actionCtx.addQuery("action",action);
            ctx.println("<br>");
            String what = actionCtx.field("srl_vet_res");
            
            Set<CLDRLocale> locs = new TreeSet<CLDRLocale>();
            
            if(what.length()>0) {
                String whats[] = UserRegistry.tokenizeLocale(what);
                
                for(String l : whats) {
                	CLDRLocale loc = CLDRLocale.getInstance(l);
                	locs.add(loc);
                }
            }
            
            final boolean reupdate = actionCtx.hasField("reupdate");

            if(what.equals("ALL")) {
                ctx.println("<h4>Update All (delete first: "+reupdate+")</h4>");
                
		startupThread.addTask(new SurveyThread.SurveyTask("UpdateAll, Delete:"+reupdate) {
		    public void run() throws Throwable {
			ElapsedTimer et = new ElapsedTimer();
			int n = vet.updateResults(reupdate);
			SurveyLog.logger.warning("Done updating "+n+" vote results in: " + et + "<br>");
			lcr.invalidateLocale(CLDRLocale.ROOT);
			ElapsedTimer zet = new ElapsedTimer();
			int zn = vet.updateStatus();
			SurveyLog.logger.warning("Done updating "+zn+" statuses [locales] in: " + zet + "<br>");
			
		    }
		    });
		       
		ctx.println("<h2>Task Queued.</h2>");
		
            } else {
            	ctx.println("<h4>Update All</h4>");
            	ctx.println("Locs: ");
            	for(CLDRLocale loc : locs ) {
            		ctx.println("("+loc+") ");
            	}
            	ctx.println("<br>");
            	ctx.println("* <a href='"+actionCtx.url()+actionCtx.urlConnector()+"srl_vet_res=ALL'>Update all (routine update)</a><p>  ");
            	ctx.println("* <a href='"+actionCtx.url()+actionCtx.urlConnector()+"srl_vet_res=ALL&reupdate=reupdate'><b>REupdate:</b> " +
            	"Delete all old results, and recount everything. Takes a long time.</a><br>");
            	if(what.length()>0) {
            		try {
            			Connection conn = null;
            			PreparedStatement rmResultLoc = null;
            			try {
            				conn = dbUtils.getDBConnection();
            				rmResultLoc = Vetting.prepare_rmResultLoc(conn);
            				for(CLDRLocale loc : locs) {
            					ctx.println("<h2>"+loc+"</h2>");
            					if(reupdate) {
            						try {
            							synchronized(vet) {
            								rmResultLoc.setString(1,loc.toString());
            								int del = DBUtils.sqlUpdate(ctx, conn, rmResultLoc);
            								ctx.println("<em>"+del+" results of "+loc+" locale removed</em><br>");
            								SurveyLog.logger.warning("update: "+del+" results of "+loc+" locale " +
            								"removed");
            							}
            						} catch(SQLException se) {
            				        	SurveyLog.logException(se, ctx);
            							se.printStackTrace();
            							ctx.println("<b>Err while trying to delete results for " + loc + ":</b> <pre>" + DBUtils.unchainSqlException(se)+"</pre>");
            						}
            					}

            					ctx.println("<h4>Update just "+loc+"</h4>");
            					ElapsedTimer et = new ElapsedTimer();
            					int n = vet.updateResults(loc,conn);
            					ctx.println("Done updating "+n+" vote results for " + loc + " in: " + et + "<br>");
            					lcr.invalidateLocale(loc);
            				}
            				ElapsedTimer zet = new ElapsedTimer();
            				int zn = vet.updateStatus(conn);
            				ctx.println("Done updating "+zn+" statuses ["+locs.size()+" locales] in: " + zet + "<br>");
            			} finally {
            				DBUtils.close(rmResultLoc,conn);
            			}
            		} catch (SQLException se) {
                    	SurveyLog.logException(se, ctx);
            			se.printStackTrace();
            			ctx.println("<b>Err while trying to delete results :</b> <pre>" + DBUtils.unchainSqlException(se)+"</pre>");
            		}
            	} else {
            		vet.stopUpdating = true;            
            	}
            }
            actionCtx.println("<hr><h4>Update just certain locales</h4>");
            actionCtx.println("<form method='POST' action='"+actionCtx.url()+"'>");
            actionCtx.printUrlAsHiddenFields();
            actionCtx.println("<label><input type='checkbox' name='reupdate' value='reupdate'>Delete old results before update?</label><br>");
            actionCtx.println("Update just this locale: <input name='srl_vet_res' value='"+what+"'><input type='submit' value='Update'></form>");
        } else if(action.equals("srl_twiddle")) {
			ctx.println("<h3>Parameters. Please do not click unless you know what you are doing.</h3>");
			
			for(Iterator i = twidHash.keySet().iterator();i.hasNext();) {
				String k = (String)i.next();
				Object o = twidHash.get(k);
				if(o instanceof Boolean) {	
					boolean adv = showToggleTwid(actionSubCtx, k, "Boolean "+k);
				} else {
					actionSubCtx.println("<h4>"+k+"</h4>");
				}
			}
			
			
//        } else if(action.equals("srl_vet_wash")) {
//        	WebContext subCtx = (WebContext)ctx.clone();
//        	actionCtx.addQuery("action",action);
//        	ctx.println("<br>");
//        	String what = actionCtx.field("srl_vet_wash");
//        	if(what.equals("ALL")) {
//        		ctx.println("<h4>Remove Old Votes. (in preparation for a new CLDR - do NOT run this after start of vetting)</h4>");
//        		ElapsedTimer et = new ElapsedTimer();
//        		int n = vet.washVotes();
//        		ctx.println("Done washing "+n+" vote results in: " + et + "<br>");
//        		int stup = vet.updateStatus();
//        		ctx.println("Updated " + stup + " statuses.<br>");
//        	} else {
//        		ctx.println("All: [ <a href='"+actionCtx.url()+actionCtx.urlConnector()+action+"=ALL'>Wash all</a> ]<br>");
//        		if(what.length()>0) {
//        			ctx.println("<h4>Wash "+what+"</h4>");
//        			ElapsedTimer et = new ElapsedTimer();
//        			int n = vet.washVotes(CLDRLocale.getInstance(what));
//        			ctx.println("Done washing "+n+" vote results in: " + et + "<br>");
//        			int stup = vet.updateStatus();
//        			ctx.println("Updated " + stup + " statuses.<br>");
//        		}
//        	}
//        	actionCtx.println("<form method='POST' action='"+actionCtx.url()+"'>");
//        	actionCtx.printUrlAsHiddenFields();
//        	actionCtx.println("Update just: <input name='"+action+"' value='"+what+"'><input type='submit' value='Wash'></form>");
        } else if(action.equals("srl_twiddle")) {
			ctx.println("<h3>Parameters. Please do not click unless you know what you are doing.</h3>");
			
			for(Iterator i = twidHash.keySet().iterator();i.hasNext();) {
				String k = (String)i.next();
				Object o = twidHash.get(k);
				if(o instanceof Boolean) {	
					boolean adv = showToggleTwid(actionSubCtx, k, "Boolean "+k);
				} else {
					actionSubCtx.println("<h4>"+k+"</h4>");
				}
			}
			
			
		} else if(action.equals("upd_src")) {
            WebContext subCtx = (WebContext)ctx.clone();
            actionCtx.addQuery("action",action);
            ctx.println("<br>(locale caches reset..)<br>");
//            Connection conn = this.getDBConnection();
            try {
//                CLDRDBSource mySrc = makeDBSource(conn, null, CLDRLocale.ROOT);
                resetLocaleCaches();
                getDBSourceFactory().manageSourceUpdates(actionCtx, this); // What does this button do?
                ctx.println("<br>");
            } finally {
//                SurveyMain.closeDBConnection(conn);
            }
            
		} else if (action.equals("srl_crash")) {
		    this.busted("User clicked 'Crash Survey Tool'");
		} else if(action.equals("srl_output")) {
			WebContext subCtx = (WebContext)ctx.clone();
			subCtx.addQuery("dump",vap);
			subCtx.addQuery("action",action);

			final String output = actionCtx.field("output");

			boolean isImmediate = actionCtx.hasField("immediate");
			
			int totalLocs = getLocales().length;
			int need[] = new int[CacheableKinds.values().length];
			
			// calculate
			if(!isImmediate) {
				try {
					Connection conn = null;
					try {
						conn = dbUtils.getDBConnection();
						for (CLDRLocale loc : getLocales()) {
							Timestamp locTime = getLocaleTime(conn, loc);
							for(SurveyMain.CacheableKinds kind : SurveyMain.CacheableKinds.values()) {
								boolean nu = fileNeedsUpdate(locTime,loc,kind.name());
								if(nu) need[kind.ordinal()]++;
							}
						}
					} finally {
						DBUtils.close(conn);
					}
				} catch (IOException e) {
		        	SurveyLog.logException(e, ctx);
					ctx.println("<i>err getting locale counts: " + e +"</i><br>");
					e.printStackTrace();
				} catch(SQLException se) {
		        	SurveyLog.logException(se, ctx);
					ctx.println("<i>err getting locale counts: " + dbUtils.unchainSqlException(se)+"</i><br>");
				}
			}

			ctx.println("<br>");
			ctx.print("<b>Output: (/"+totalLocs+")</b> ");
			printMenu(subCtx, output, "xml", "XML ("+need[CacheableKinds.xml.ordinal()]+"/)", "output");
			subCtx.print(" | ");
			printMenu(subCtx, output, "vxml", "VXML ("+need[CacheableKinds.vxml.ordinal()]+"/)", "output");
			subCtx.print(" | ");
			printMenu(subCtx, output, "rxml", "RXML (SLOW!) ("+need[CacheableKinds.rxml.ordinal()]+"/)", "output");
			subCtx.print(" | ");
			printMenu(subCtx, output, "sql", "SQL", "output");
			subCtx.print(" | ");
			printMenu(subCtx, output, "misc", "MISC", "output");
			subCtx.print(" | ");
			printMenu(subCtx, output, "daily", "DAILY", "output");
			subCtx.print(" | ");

			ctx.println("<br>");

			ElapsedTimer aTimer = new ElapsedTimer();
			int files = 0;
			final boolean daily = output.equals("daily");

			if(daily && !isImmediate) {
				startupThread.addTask(new SurveyThread.SurveyTask("daily xml output")
				{
					final String dailyList[] = {
//							"xml",
//							"vxml",
							"users",
							"usersa",
							"translators",
							"txml",
					};          
					public void run() throws Throwable 
					{
						CLDRProgressTask progress = openProgress("xml output", dailyList.length);
						try {

							String origName = name;
							for(int i=0;running()&&(i<dailyList.length);i++) {
								String what = dailyList[i];
								progress.update(i,what);
								doOutput(what);
							}
						} finally {
							progress.close();
						}
					}
				});
				ctx.println("<h2>Task Queued.</h2>");
			} else {
				if(isImmediate) {
					if(daily || output.equals("xml")) {
						files += doOutput("xml");
						ctx.println("xml" + "<br>");
					}
					if(daily || output.equals("vxml")) {
						files += doOutput("vxml");
						ctx.println("vxml" + "<br>");
					}
					if(output.equals("rxml")) {
						files += doOutput("rxml");
						ctx.println("rxml" + "<br>");
					}
					if(output.equals("sql")) {
						files += doOutput("sql");
						ctx.println("sql" + "<br>");
					}
					if(daily || output.equals("misc")) {
						files += doOutput("users");
						ctx.println("users" + "<br>");
						files += doOutput("usersa");
						ctx.println("usersa" + "<br>");
						files += doOutput("translators");
						ctx.println("translators" + "<br>");
					}

					if(output.length()>0) {
						ctx.println("<hr>"+output+" completed with " + files + " files in "+aTimer+"<br>");
					}
				} else {
					startupThread.addTask(new SurveyThread.SurveyTask("admin output")
					{
						public void run() throws Throwable 
						{
							int files=0;
							CLDRProgressTask progress = openProgress("admin output", 9);
							try {
								progress.update("xml?");
								if(daily || output.equals("xml")) {
									files += doOutput("xml");
								}
								progress.update("vxml?");
								if(daily || output.equals("vxml")) {
									files += doOutput("vxml");
								}
								progress.update("rxml?");
								if(output.equals("rxml")) {
									files += doOutput("rxml");
								}
								progress.update("sql?");
								if(output.equals("sql")) {
									files += doOutput("sql");
								}
								progress.update("misc?");
								if(daily || output.equals("misc")) {
									progress.update("users?");
									files += doOutput("users");
									progress.update("usersa?");
									files += doOutput("usersa");
									progress.update("translators?");
									files += doOutput("translators");
								}
								progress.update("finishing");
							} finally {
								progress.close();
							}
						}
					});
				}      
			}
		} else if(action.equals("srl_db_update")) {
            WebContext subCtx = (WebContext)ctx.clone();
            subCtx.addQuery("dump",vap);
            subCtx.addQuery("action","srl_db_update");
            ctx.println("<br>");
//            XMLSource mySrc = makeDBSource(ctx, null, CLDRLocale.ROOT);
            ElapsedTimer aTimer = new ElapsedTimer();
            getDBSourceFactory().doDbUpdate(subCtx, this); 
            ctx.println("<br>(dbupdate took " + aTimer+")");
            ctx.println("<br>");
		} else if(action.equals("srl_vxport")) {
			SurveyLog.logger.warning("vxport");
			File inFiles[] = getInFiles();
			int nrInFiles = inFiles.length;
			boolean found = false;
			String theLocale = null;
			File outdir = new File("./xport/");
			for(int i=0;(!found) && (i<nrInFiles);i++) {
			 try{
				String localeName = inFiles[i].getName();
				theLocale = fileNameToLocale(localeName).getBaseName();
				SurveyLog.logger.warning("#vx "+theLocale);
				XMLSource dbSource = makeDBSource(CLDRLocale.getInstance(theLocale), true);
				CLDRFile file = makeCLDRFile(dbSource).setSupplementalDirectory(supplementalDataDir);
				  OutputStream files = new FileOutputStream(new File(outdir,localeName),false); // Append
//				  PrintWriter pw = new PrintWriter(files);
	//            file.write(WebContext.openUTF8Writer(response.getOutputStream()));
				PrintWriter ow;
				file.write(ow=WebContext.openUTF8Writer(files));
				ow.close();
//				pw.close();
				files.close();
				
				} catch(IOException exception){
		        	SurveyLog.logException(exception, ctx);
				}
			}
		} else if(action.equals("add_locale")) {
			actionCtx.addQuery("action", action);
			ctx.println("<hr><br><br>");
			String loc = actionCtx.field("loc");
			
			ctx.println("<div class='ferrbox'><B>Note:</B> before using this interface, you must read <a href='http://cldr.unicode.org/development/adding-locales'>This Page</a> especially about adding core data.</div>");

			ctx.println("This interface lets you create a new locale, and its parents.  Before continuing, please make sure you have done a" +
					" SVN update to make sure the file doesn't already exist." +
					" After creating the locale, it should be added to SVN as well.<hr>");
			
			ctx.print("<form action='"+actionCtx.base()+"'>");
            ctx.print("<input type='hidden' name='action' value='"+action+"'>");
            ctx.print("<input type='hidden' name='dump' value='"+vap+"'>");			
			ctx.println("<label>Add Locale: <input name='loc' value='"+loc+"'></label>");
			ctx.println("<input type=submit value='Check'></form>");
			
			
			
			if(loc.length()>0) {
				ctx.println("<hr>");
				CLDRLocale cloc = CLDRLocale.getInstance(loc);
				Set<CLDRLocale> locs = this.getLocalesSet();
				
				int numToAdd=0;
				String reallyAdd = ctx.field("doAdd");
				boolean doAdd = reallyAdd.equals(loc);
				
				for(CLDRLocale aloc : cloc.getParentIterator()) {
					ctx.println("<b>"+aloc.toString()+"</b> : " + aloc.getDisplayName(ctx.displayLocale)+"<br>");
					ctx.print("<blockquote>");
					try {
						if(locs.contains(aloc)) { 
							ctx.println(
									ctx.iconHtml("squo", "done with this locale")+
									"... already installed.<br>");
							continue;
						}
				        File baseDir = new File(fileBase);
				        File xmlFile = new File(baseDir,aloc.getBaseName()+".xml");
						if(xmlFile.exists()) { 
							ctx.println(
									ctx.iconHtml("ques", "done with this locale")+
									"... file ( " + xmlFile.getAbsolutePath() +" ) exists!. [consider update]<br>");
							continue;
						}
						
						if(!doAdd) {
							ctx.println(ctx.iconHtml("star", "ready to add!")+
									" ready to add " + xmlFile.getName() +"<br>");
							numToAdd++;
						} else {
							CLDRFile emptyFile = SimpleFactory.makeFile(aloc.getBaseName());
		                    try {
		                        PrintWriter utf8OutStream = new PrintWriter(
		                            new OutputStreamWriter(
		                                new FileOutputStream(xmlFile), "UTF8"));
		                        emptyFile.write(utf8OutStream);
		                        utf8OutStream.close();
								ctx.println(ctx.iconHtml("okay", "Added!")+
										" Added " + xmlFile.getName() +"<br>");
								numToAdd++;
						        //            } catch (UnsupportedEncodingException e) {
						        //                throw new InternalError("UTF8 unsupported?").setCause(e);
		                    } catch (IOException e) {
		                    	SurveyLog.logException(e, ctx);
		                    	SurveyLog.logger.warning("While adding "+xmlFile.getAbsolutePath());
		                        e.printStackTrace();
		                        ctx.println(ctx.iconHtml("stop","err")+" Error While adding "+xmlFile.getAbsolutePath()+" - " + e.toString()+"<br><pre>");
		                        ctx.print(e);
		                        ctx.print("</pre><br>");
		                    }

							
						}
						
					} finally {
						ctx.print("</blockquote>");
					}
				}
				if(!doAdd && numToAdd>0) {
					ctx.print("<form action='"+actionCtx.base()+"'>");
		            ctx.print("<input type='hidden' name='action' value='"+action+"'>");
		            ctx.print("<input type='hidden' name='dump' value='"+vap+"'>");			
					ctx.print("<input type='hidden' name='loc' value='"+loc+"'></label>");
					ctx.print("<input type='hidden' name='doAdd' value='"+loc+"'></label>");
					ctx.print("<input type=submit value='Add these "+numToAdd+" file(s) for "+loc+"!'></form>");
				} else if(doAdd) {
					ctx.print("<br>Added " + numToAdd +" files.<br>");
					this.resetLocaleCaches();
					ctx.print("<br>Locale caches reset. Remember to check in the file(s).<br>");
				} else {
					ctx.println("(No files would be added.)<br>");
				}
			}
        } else if(action.equals("load_all")) {
            File[] inFiles = getInFiles();
            int nrInFiles = inFiles.length;

            actionCtx.addQuery("action",action);
            ctx.println("<hr><br><br>");
            if(!actionCtx.hasField("really_load")) {
                actionCtx.addQuery("really_load","y");
                ctx.println("<b>Really Load "+nrInFiles+" locales?? <a class='ferrbox' href='"+actionCtx.url()+"'>YES</a><br>");
            } else {
            	
            	startupThread.addTask(new SurveyThread.SurveyTask("load all locales")
            	{
	            	public void run() throws Throwable 
	                {
	                	loadAllLocales(null,this);
	                    ElapsedTimer et = new ElapsedTimer();
	                    int n = vet.updateStatus();
	                    SurveyLog.logger.warning("Done updating "+n+" statuses [locales] in: " + et + "<br>");
	                }
            	});
		ctx.println("<h2>Task Queued.</h2>");
            }
            
        } else if(action.equals("specialusers")) {
            ctx.println("<hr>Re-reading special users list...<br>");
            Set<UserRegistry.User> specials = reg.getSpecialUsers(true); // force reload
            if(specials==null) {
                ctx.println("<b>No users are special.</b> To make them special (allowed to vet during closure) <code>specialusers.txt</code> in the cldr directory. Format as follows:<br> "+
                            "<blockquote><code># this is a comment<br># The following line makes user 295 special<br>295<br></code>"+
                            "</code></blockquote><br>");
            } else {
                ctx.print("<br><hr><i>Users allowed special access:</i>");
                ctx.print("<table class='list' border=1 summary='special access users'>");
                ctx.print("<tr><th>"+specials.size()+" Users</th></tr>");
                int nn=0;
                for(UserRegistry.User u : specials) {
                    ctx.println("<tr class='row"+(nn++ % 2)+"'>");
                    ctx.println("<td>"+u.toString()+"</td>");
                    ctx.println("</tr>");
                }
                ctx.print("</table>");
            }
        } else if(action.equals("specialmsg")) {
            ctx.println("<hr>");
            
            // OGM---
            // seconds
            String timeQuantity = "seconds";
            long timeInMills = (1000);
            /*
            // minutes
             String timeQuantity = "minutes";
             long timeInMills = (1000)*60;
            */
            ctx.println("<h4>Set outgoing message (leave blank to unset)</h4>");
            long now = System.currentTimeMillis();
            if(ctx.field("setogm").equals("1")) {
                specialHeader=ctx.field("ogm");
                if(specialHeader.length() ==0) {
                    specialTimer = 0;
                } else {
                    long offset = ctx.fieldInt("ogmtimer",-1);
                    if(offset<0) {
                        // no change.
                    } else if(offset == 0) {
                        specialTimer = 0; // clear
                    } else {
                        specialTimer = (timeInMills * offset) + now;
                    }
                }
                String setlockout = ctx.field("setlockout");
                if(lockOut != null) {
                    if(setlockout.length()==0) {
                        ctx.println("Lockout: <b>cleared</b><br>");
                        lockOut = null;
                    } else if(!lockOut.equals(setlockout)) {
                        lockOut = setlockout;
                        ctx.println("Lockout changed to: <tt class='codebox'>"+lockOut+"</tt><br>");
                    }
                } else {
                    if(setlockout.length()>0) {
                        lockOut = setlockout;
                        ctx.println("Lockout set to: <tt class='codebox'>"+lockOut+"</tt><br>");
                    }
                }
            }
            if((specialHeader != null) && (specialHeader.length()>0)) {
                ctx.println("<div style='border: 2px solid gray; margin: 0.5em; padding: 0.5em;'>" + specialHeader + "</div><br>");
                if(specialTimer == 0) {
                    ctx.print("Timer is <b>off</b>.<br>");
                } else if(now>specialTimer) {
                    ctx.print("Timer is <b>expired</b><br>");
                } else {
                    ctx.print("Timer remaining: " + timeDiff(now,specialTimer));
                }
            } else {
                ctx.println("<i>none</i><br>");
            }
            ctx.print("<form action='"+actionCtx.base()+"'>");
            ctx.print("<input type='hidden' name='action' value='"+"specialmsg"+"'>");
            ctx.print("<input type='hidden' name='dump' value='"+vap+"'>");
            ctx.print("<input type='hidden' name='setogm' value='"+"1"+"'>");
            ctx.print("<label>Message: <input name='ogm' value='"+((specialHeader==null)?"":specialHeader.replaceAll("'","\"").replaceAll(">","&gt;"))+
                    "' size='80'></label><br>");
            ctx.print("<label>Timer: (use '0' to clear) <input name='ogmtimer' size='10'>"+timeQuantity+"</label><br>");
            ctx.print("<label>Lockout Password:  [unlock=xxx] <input name='setlockout' value='"+
                ((lockOut==null)?"":lockOut)+"'></label><br>");
            ctx.print("<input type='submit' value='set'>");
            ctx.print("</form>");
            // OGM---
        } else if(action.equals("srl_test0")) {
            String test0 = ctx.field("test0");
            
            ctx.print("<h1>test0 over " + test0 + "</h1>");
            ctx.print("<i>Note: xpt statistics: " + xpt.statistics() +"</i><hr>");
            SurveyMain.throwIfBadLocale(test0);
            ctx.print(new ElapsedTimer("Time to do nothing: {0}").toString()+"<br>");
            
            // collect paths
            ElapsedTimer et = new ElapsedTimer("Time to collect xpaths from " + test0 + ": {0}");
            Set<Integer> paths = new HashSet<Integer>();
            String sql = "SELECT xpath from CLDR_DATA where locale=\""+test0+"\"";
            try {
                Connection conn = dbUtils.getDBConnection();
                Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery(sql);
                while(rs.next()) {
                    paths.add(rs.getInt(1));
                }
                rs.close();
                s.close();
            } catch ( SQLException se ) {
            	SurveyLog.logException(se, ctx);
                String complaint = " Couldn't query xpaths of " + test0 +" - " + DBUtils.unchainSqlException(se) + " - " + sql;
                SurveyLog.logger.warning(complaint);
                ctx.println("<hr><font color='red'>ERR: "+complaint+"</font><hr>");
            }
            ctx.print("Collected "+paths.size()+" paths, " + et + "<br>");
            
            // Load paths
            et = new ElapsedTimer("load time: {0}");
            for(int xp : paths) {
                xpt.getById(xp);
            }
            ctx.print("Load "+paths.size()+" paths from " + test0 + " : " + et+"<br>");
            
            final int TEST_ITER=100000;
            et = new ElapsedTimer("Load " + TEST_ITER+"*"+paths.size()+"="+(TEST_ITER*paths.size())+" xpaths: {0}");
            for(int j=0;j<TEST_ITER;j++) {
                for(int xp : paths) {
                    xpt.getById(xp);
                }
            }
            ctx.print("Test: " + et+ "<br>");
       } else if(action.length()>0) {
            ctx.print("<h4 class='ferrbox'>Unknown action '"+action+"'.</h4>");
        }
                
        printFooter(ctx);
    }

    public static void appendMemoryInfo(StringBuffer buf, boolean inline) {
        Runtime r = Runtime.getRuntime();
        double total = r.totalMemory();
        total = total / 1024000.0;
        double free = r.freeMemory();
        free = free / 1024000.0;        
        
        String info = ("Free memory: " + free + "M / " + total + "M.");
        if(inline) {
        	buf.append("<span title='"+info+"'>");
        }
        SurveyProgressManager.appendProgressBar(buf, total-free, total);
        if(inline) {
        	buf.append("</span>");
        } else {
        	buf.append(info+"<br/>");
        }
     }
    /* print a warning the first time. */
    
    public static class OnceWarner {
	boolean warnOnce;
	public OnceWarner(boolean warnOnce) {
	    this.warnOnce = warnOnce;
	}
	public OnceWarner() {
	    this.warnOnce = true;
	}
	private class OnceEntry {
	    /**
	     * @return # of times we've been nudged, inclusively
	     */
	    public int nudge() { return count++;  }
	    public OnceEntry(String desc) { this.desc = desc; }
	    public int count() { return count; }
	    public String desc() { return desc; }
	    String desc;
	    int count=0;
	}
	private Map<String,OnceEntry> theMap = new TreeMap<String,OnceEntry>();
	private OnceEntry peekEntry(String key) {
	    return theMap.get(key);
	}
	private OnceEntry getEntry(String key, String desc) {
	    OnceEntry ent = peekEntry(key);
	    if(ent==null) {
		theMap.put(key,(ent=new OnceEntry(desc)));
	    }
	    return ent;
	}
	public int count(String key) {
	    OnceEntry ent = peekEntry(key);
	    if(ent!=null) {
		return ent.count();
	    } else {
		return 0;
	    }
	}
	public int warnOnce(WebContext ctx, String key, String icon, String desc, String longDesc) {
	    int n = getEntry(key,desc).nudge();
	    if(n==1 || !warnOnce) {
		ctx.println(ctx.iconHtml(icon, "counter")+" "+desc+ (warnOnce?" (this message prints only once)":"")+"|"+longDesc+"<br>");
	    }
	    return n;
	}
	public int warnOnce(WebContext ctx, String key, String icon, String desc) {
	    return warnOnce(ctx,key,icon,desc,"");
	}
	public void summarize(WebContext ctx) {
	    int n=0;
	    if(theMap.isEmpty()) return;
	    ctx.println("<table class='tzbox'><tr><th>var</th><th>#</th><th>desc</th></tr>");
	    for(Map.Entry<String,OnceEntry> e : theMap.entrySet()) {
		String k = e.getKey();
		OnceEntry v = e.getValue();

		ctx.println("<tr class='r"+(n%2)+"'><th>"+k+"</th><td>"+v.count()+"</td><td>"+v.desc()+"</td></tr>");
	    }
	    ctx.println("</table>");
	}
    }

    private static void throwIfBadLocale(String test0) {
        if(!SurveyMain.getLocalesSet().contains(CLDRLocale.getInstance(test0))) {
            throw new InternalError("Bad locale: "+test0);
        }
    }
	private void loadAllLocales(WebContext ctx, SurveyTask surveyTask) {
	    File[] inFiles = getInFiles();
	    int nrInFiles = inFiles.length;
	    com.ibm.icu.dev.test.util.ElapsedTimer allTime = new com.ibm.icu.dev.test.util.ElapsedTimer("Time to load all: {0}");
	    SurveyLog.logger.info("Loading all..");            
	    //        Connection connx = null;
	    int ti = 0;
	    CLDRProgressTask progress = null;
	    if(surveyTask!=null&&surveyTask.running()) {
	        progress = surveyTask.openProgress("Loading All Locales", nrInFiles);
	    }
	    try {
	        for(int i=0;(null==this.isBusted)&&i<nrInFiles&&(surveyTask==null||surveyTask.running());i++) {
	            String localeName = inFiles[i].getName();
	            int dot = localeName.indexOf('.');
	            if(dot !=  -1) {
	                localeName = localeName.substring(0,dot);
	                if(progress!=null) {
	                    progress.update(i,localeName);
	                }
	                if((i>0)&&((i%50)==0)) {
	                    SurveyLog.logger.info("   "+ i + "/" + nrInFiles + ". " + usedK() + "K mem used");
	                    //if(ctx!=null) ctx.println("   "+ i + "/" + nrInFiles + ". " + usedK() + "K mem used<br>");
	                }
	                try {
	                    //                    if(connx == null) {
	                    //                        connx = this.getDBConnection();
	                    //                    }

	                    CLDRLocale locale = CLDRLocale.getInstance(localeName);
	                    getDBSourceFactory().getInstance(locale);
	                    //                        WebContext xctx = new WebContext(false);
	                    //                        xctx.setLocale(locale);
	                    //makeCLDRFile(makeDBSource(connx, null, locale));  // orphan result
	                } catch(Throwable t) {
	                	SurveyLog.logException(t, ctx);
	                    t.printStackTrace();
	                    String complaint = ("Error loading: " + localeName + " - " + t.toString() + " ...");
	                    SurveyLog.logger.severe("loading all: " + complaint);
	                    throw new InternalError("loading all: " + complaint);
	                }
	            }
	        }
	        if(surveyTask!=null&&!surveyTask.running()) {
	            SurveyLog.logger.warning("LoadAll: no longer running!\n");
	        }
	        if(surveyTask != null && surveyTask.running()) {
	        }
	        //        closeDBConnection(connx);
	        SurveyLog.logger.info("Loaded all. " + allTime);
	        //	        if(ctx!=null) ctx.println("Loaded all." + allTime + "<br>");
	        int n = getDBSourceFactory().update(surveyTask, null);
	        SurveyLog.logger.info("Updated "+n+". " + allTime);
	        //	        if(ctx!=null) ctx.println("Updated "+n+"." + allTime + "<br>");
	    } finally {
	       if(progress != null) {
	           progress.close();
	       }
	    }
	}
    
    /* 
     * print menu of stuff to 'work with' a live user session..
     */
    private void printLiveUserMenu(WebContext ctx, CookieSession cs) {
        ctx.println("<a href='" + ctx.base() + "?dump=" + vap + "&amp;see=" + cs.id + "'>" + ctx.iconHtml("zoom","SEE this user") + "see" + "</a> |");
        ctx.println("<a href='" + ctx.base() + "?&amp;s=" + cs.id + "'>"  +"be" + "</a> |");
        ctx.println("<a href='" + ctx.base() + "?dump=" + vap + "&amp;unlink=" + cs.id + "'>" +  "kick" + "</a>");
    }
    
//    static boolean showedComplaint = false;
     
    /**
    * print the header of the thing
    */
    public void printHeader(WebContext ctx, String title)
    {
        ctx.println("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\" \"http://www.w3.org/TR/html4/loose.dtd\">");
        ctx.println("<html>");
        ctx.println("<head>");
/*
        if(showedComplaint == false) {
            showedComplaint = true;
            SurveyLog.logger.warning("**** noindex,nofollow is disabled");
        }
        */
        ctx.println("<META NAME=\"ROBOTS\" CONTENT=\"NOINDEX,NOFOLLOW\"> "); // NO index
        ctx.println("<meta name='robots' content='noindex,nofollow'>");
        ctx.println("<meta name=\"gigabot\" content=\"noindex\">");
        ctx.println("<meta name=\"gigabot\" content=\"noarchive\">");
        ctx.println("<meta name=\"gigabot\" content=\"nofollow\">");

        ctx.includeAjaxScript(AjaxType.STATUS);
        ctx.println("<link rel='stylesheet' type='text/css' href='"+ ctx.schemeHostPort()  + ctx.context("surveytool.css") + "'>");
        ctx.println("<title>Survey Tool | ");
        if(ctx.getLocale() != null) {
            ctx.print(ctx.getLocale().getDisplayName(ctx.displayLocale) + " | ");
        }
        ctx.println(title + "</title>");
        ctx.println("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">");
        
        if(true || isUnofficial || 
                ctx.prefBool(PREF_GROTTY)) {  // no RSS on the official site- for now
            if(ctx.getLocale() != null) {
                ctx.println(fora.forumFeedStuff(ctx));
            } else {
                if(!ctx.hasField("x")&&!ctx.hasField("do")&&!ctx.hasField("sql")&&!ctx.hasField("dump")) {
                    ctx.println(fora.mainFeedStuff(ctx));
                }
            }
        }
        
        ctx.put("TITLE", title);
        ctx.includeFragment("st_top.jsp");
        ctx.no_js_warning();
    }
    
    void showSpecialHeader(WebContext ctx) {
        showSpecialHeader(ctx, ctx.getOut());
    }
    
    void showSpecialHeader(PrintWriter out) {
        showSpecialHeader(null, out);
    }    
    
    /**
     * print the top news banner. this must callable by non-context functions.
     * @param out output stream - must be set
     * @param ctx context - optional. 
     */
    void showSpecialHeader(WebContext ctx, PrintWriter out) {
        out.print(getSpecialHeader(ctx));
    }
    
    public String getSpecialHeader() {
        return getSpecialHeader(null);
    }
    
    public String getSpecialHeader(WebContext ctx) {
        StringBuffer out = new StringBuffer();
        if((specialHeader != null) && (specialHeader.length()>0)) {
            out.append("<div class='specialHeader'>");
            if(ctx != null) {
                out.append("News");
                //ctx.printHelpLink("/BannerMessage","News",true,false);
                out.append(": &nbsp; ");
            }
            out.append(specialHeader);
            if(specialTimer != 0) {
                long t0 = System.currentTimeMillis();
                out.append("<br><b>Timer:</b> ");
                if(t0 > specialTimer) {
                    out.append("<b>The countdown time has arrived.</b>");
                } else {
                    out.append("The countdown timer has " + timeDiff(t0,specialTimer) +" remaining on it.");
                }
            }
            out.append("<br>");
            String threadInfo = startupThread.htmlStatus();
            if(threadInfo!=null) {
            	out.append("<b>Processing:"+threadInfo+"</b><br>");
            }
            out.append(getProgress());
            out.append("</div><br>");
        } else {
            String threadInfo = startupThread.htmlStatus();
            if(threadInfo!=null) {
            	out.append("<b>Processing:"+threadInfo+"</b><br>");
            }
            out.append(getProgress());
        }
        return out.toString();
    }
    
    /**
     * Return the entire top 'box' including progress bars, busted notices, etc.
     * @return
     */
    public String getTopBox() {
    	return getTopBox(true);
    }
    /**
     * Return the entire top 'box' including progress bars, busted notices, etc.
     * @return
     */
    public String getTopBox(boolean shorten) {
        StringBuffer out = new StringBuffer();
        if(isBusted!=null) {
            out.append("<h1>The CLDR Survey Tool is offline</h1>");
            out.append("<div class='ferrbox'><pre>" + isBusted +"</pre><hr>");
            String stack = SurveyForum.HTMLSafe(isBustedStack).replaceAll("\t", "&nbsp;&nbsp;&nbsp;").replaceAll("\n", "<br>");
            if(shorten) {
            	out.append(getShortened(stack));
            } else {
            	out.append(stack);
            }
            out.append("</div><br>");
        }
        if(lockOut != null) {
            out.append("<h1>The CLDR Survey Tool is Locked for Maintenance</h1>");
        }
        out.append(getSpecialHeader());
        return out.toString();
    }
    
    public static final int PROGRESS_WID=100; /** Progress bar width **/
        
    
    /* (non-Javadoc)
     * @see org.unicode.cldr.web.CLDRProgressIndicator#openProgress(java.lang.String)
     */
    @Override
    public CLDRProgressTask openProgress(String what) {
        return openProgress(what, -100);
    }
    
    /* (non-Javadoc)
     * @see org.unicode.cldr.web.CLDRProgressIndicator#openProgress(java.lang.String, int)
     */
    @Override
    public CLDRProgressTask openProgress(String what, int max) {
        return progressManager.openProgress(what,max);
    }
    
    
    /**
     * Print out the progress
     * @param ctx
     */
    public void showProgress(WebContext ctx) {
        ctx.print(getProgress());
    }
    
    /**
     * Return the current progress indicator.
     * @return
     */
    public String getProgress() {
        return progressManager.getProgress();
    }
    
    public void printFooter(WebContext ctx)
    {
        ctx.println("<hr>");
        ctx.print("<div style='float: right; font-size: 60%;'>");
        ctx.print("<span style='color: #ddd'> "+SURVEYMAIN_REVISION+" \u00b7 </span>");
        ctx.print("<span class='notselected'>validate <a href='http://jigsaw.w3.org/css-validator/check/referer'>css</a>, "+
            "<a href='http://validator.w3.org/check?uri=referer'>html</a></span>");
        ctx.print(" \u00b7 <span id='visitors'>");
        ctx.print(getGuestsAndUsers());
        ctx.print("</span> \u00b7 ");
        ctx.print(" served in " + ctx.reqTimer + "</div>");
        ctx.println("<a href='http://www.unicode.org'>Unicode</a> | <a href='"+URL_CLDR+"'>Common Locale Data Repository</a>");
        if(ctx.request != null) try {
            Map m = new TreeMap(ctx.getParameterMap());
            m.remove("sql");
            m.remove("pw");
            m.remove(QUERY_PASSWORD_ALT);
            m.remove("email");
            m.remove("dump");
            m.remove("s");
            m.remove("udump");
            String u = "";
            for(Enumeration e=ctx.request.getParameterNames();e.hasMoreElements();)  {
                String k = e.nextElement().toString();
                String v;
                if(k.equals("sql")||k.equals("pw")||k.equals("email")||k.equals("dump")||k.equals("s")||k.equals("udump")) {
                    v = "";
                } else {
                    v = ctx.request.getParameterValues(k)[0];
                }
                u=u+"|"+k+"="+v;
            }
            ctx.println("| <a " + (isUnofficial?"title":"href") + "='" + bugFeedbackUrl("Feedback on URL ?" + u)+"'>Report Problem in Tool</a>");
        } catch (Throwable t) {
        	SurveyLog.logException(t, ctx);
            SurveyLog.logger.warning(t.toString());
            t.printStackTrace();
        }
        if(!SurveyMain.isUnofficial) {
        	ctx.println(ShowData.ANALYTICS);
        }
        ctx.println("</body>");
        ctx.println("</html>");
    }
    
    public static String getGuestsAndUsers() {
        StringBuffer out = new StringBuffer();
        int guests = CookieSession.nGuests;
        int users = CookieSession.nUsers;
        if((guests+users)>0) { // ??
            out.append("~");
            if(users>0) {
                out.append(users+" users");
            }
            if(guests>0) {
                if(users>0) {
                    out.append(", ");
                }
                out.append(" "+guests+" guests");
            }
        }
        out.append(", "+pages+"pg/"+uptime);
        double procs = osmxbean.getAvailableProcessors();
        double load = osmxbean.getSystemLoadAverage();
        if(load>0.0) {
            int n=256-(int)Math.floor((load/procs)*256.0);
            String asTwoHexString=Integer.toHexString(n);
        	out.append("/<span title='Total System Load' style='background-color: #ff");
	        if(asTwoHexString.length()==1) {
	        	out.append("0");
	        	out.append(asTwoHexString);
	        	out.append("0");
	        	out.append(asTwoHexString);
	        } else {
	        	out.append(asTwoHexString);
	        	out.append(asTwoHexString);
	        }
	        out.append("'>load:"+(int)Math.floor(load*100.0)+"%</span>");
        }
        {
        	DBUtils theDb = DBUtils.peekInstance();
        	if(theDb!=null) {
        		try {
        			out.append(" <span title='DB Connections/Max Connections'>db:");
					theDb.statsShort(out);
					out.append("</span>");
				} catch (IOException e) {
					//e.printStackTrace();
				}
        	}
        }
        return out.toString();
    }
    /**
     * process the '_' parameter, if present, and set the locale.
     */
    public void setLocale(WebContext ctx)
    {
        String locale = ctx.field(QUERY_LOCALE);
        if(locale != null) {  // knock out some bad cases
            if((locale.indexOf('.') != -1) ||
               (locale.indexOf('/') != -1)) {
                locale = null;
            }
        }
        // knock out nonexistent cases.
        if(locale != null) {
            File inFiles[] = getInFiles();
            int nrInFiles = inFiles.length;
            boolean found = false;
            
            for(int i=0;(!found) && (i<nrInFiles);i++) {
                String localeName = inFiles[i].getName();
                int dot = localeName.indexOf('.');
                if(dot !=  -1) {
                    localeName = localeName.substring(0,dot);
                }
                if(localeName.equals(locale)) {
                    found = true;
                }
            }
            if(!found) {
                locale = null;
            }
        }
        if(locale != null && (locale.length()>0)) {
            ctx.setLocale(CLDRLocale.getInstance(locale));
        }
    }
    
    /**
    * set the session.
     */
    String setSession(WebContext ctx) {
        String message = null;
        // get the context
        CookieSession mySession = null;
        String myNum = ctx.field(QUERY_SESSION);
        // get the uid
        String password = ctx.field(QUERY_PASSWORD);
        if(password.isEmpty()) {
            password = ctx.field(QUERY_PASSWORD_ALT);
        }
        boolean letmein = vap.equals(ctx.field("letmein"));
        String email = ctx.field(QUERY_EMAIL);
        if("admin@".equals(email) && vap.equals(password)) {
        	letmein = true; /* don't require the DB password from admin, VAP is ok */
        }
        
        {
            String myEmail = ctx.getCookieValue(QUERY_EMAIL);
            String myPassword = ctx.getCookieValue(QUERY_PASSWORD);
            if(myEmail!=null && (email==null||email.isEmpty())) {
                email=myEmail;
            }
            if(myPassword!=null && (password == null||password.isEmpty())) {
                password = myPassword;
            }
        }
        UserRegistry.User user;
//        /*srl*/ SurveyLog.logger.warning("isBusted: " + isBusted + ", reg: " + reg);
	
//        SurveyLog.logger.warning("reg.get  pw="+password+", email="+email+", lmi="+ctx.field("letmein")+", lmigood="+vap.equals(ctx.field("letmein")));

        user = reg.get(password,email,ctx.userIP(), letmein);
        if(user!=null) {
            user.touch();
        }
	//	SurveyLog.logger.warning("user= "+user);

        if(ctx.request == null && ctx.session != null) {
            return "using canned session"; // already set - for testing
        }

        HttpSession httpSession = ctx.request.getSession(true);
        boolean idFromSession = false;
        if(myNum.equals(SURVEYTOOL_COOKIE_NONE)) {
            httpSession.removeAttribute(SURVEYTOOL_COOKIE_SESSION);
        }
        if(user != null) {
            mySession = CookieSession.retrieveUser(user.email);
            if(mySession != null) {
                if(null == CookieSession.retrieve(mySession.id)) {
                    mySession = null; // don't allow dead sessions to show up via the user list.
                } else {
//                    message = "<i id='sessionMessage'>Reconnecting to your previous session.</i>";
                    myNum = mySession.id;
                }
            }
        }

        // Retreive a number from the httpSession if present
        if((httpSession != null) && (mySession == null) && ((myNum == null) || (myNum.length()==0))) {
            String aNum = (String)httpSession.getAttribute(SURVEYTOOL_COOKIE_SESSION);
            if((aNum != null) && (aNum.length()>0)) {
                myNum = aNum;
                idFromSession = true;
            }
        } 
        
        if((mySession == null) && (myNum != null) && (myNum.length()>0)) {
            mySession = CookieSession.retrieve(myNum);
            if(mySession == null) {
                idFromSession = false;
            }
            if((mySession == null)&&(!myNum.equals(SURVEYTOOL_COOKIE_NONE))) {
                message = "<i id='sessionMessage'>(Sorry, This session has expired. ";
                if(user == null) {
                    message = message + "You may have to log in again. ";
                }
                    message = message + ")</i><br>";
            }
        }
        if((idFromSession==false) && (httpSession!=null) && (mySession!=null)) { // can we elide the 's'?
            String aNum = (String)httpSession.getAttribute(SURVEYTOOL_COOKIE_SESSION);
            if((aNum != null) && (mySession.id.equals(aNum))) {
                idFromSession = true; // it would have matched.
            } else {
       //         ctx.println("[Confused? cs="+aNum +", s=" + mySession.id + "]");
            }
        }
        // Can go from anon -> logged in.
        // can NOT go from one logged in account to another.
        if((mySession!=null) &&
           (mySession.user != null) &&
           (user!=null) &&
           (mySession.user.id != user.id)) {
            mySession = null; // throw it out.
        }
        
        if(mySession==null && user==null) {
            mySession = CookieSession.checkForAbuseFrom(ctx.userIP(), BAD_IPS, ctx.request.getHeader("User-Agent"));
            if(mySession!=null) {
                ctx.println("<h1>Note: Your IP, " + ctx.userIP() + " has been throttled for making " + BAD_IPS.get(ctx.userIP()) + " connections. Try turning on cookies, or obeying the 'META ROBOTS' tag.</h1>");
                ctx.flush();
//                try {
//                    Thread.sleep(15000);
//                } catch(InterruptedException ie) {
//                }
                ctx.session = null;
//                ctx.println("Now, go away.");
                return "Bad IP.";
            }
        }
        if(mySession == null) {
            mySession = new CookieSession(user==null, ctx.userIP());
            if(!myNum.equals(SURVEYTOOL_COOKIE_NONE)) {
//                ctx.println("New session: " + mySession.id + "<br>");
            }
            idFromSession = false;
        }
        ctx.session = mySession;
        
        if(!idFromSession) { // suppress 's' if cookie was valid
            ctx.addQuery(QUERY_SESSION, mySession.id);
        } else {
      //      ctx.println("['s' suppressed]");
        }

        if(httpSession != null) {
            httpSession.setAttribute(SURVEYTOOL_COOKIE_SESSION, mySession.id);
            httpSession.setMaxInactiveInterval(CookieSession.USER_TO / 1000);
        }
        
        if(user != null) {
            ctx.session.setUser(user); // this will replace any existing session by this user.
            ctx.session.user.ip = ctx.userIP();
        } else {
            if( (email !=null) && (email.length()>0)) {
                message = "<strong id='sessionMessage'>"+(ctx.iconHtml("stop", "failed login")+"login failed.</strong><br>");
            }
        }
        CookieSession.reap();
        return message;
    }
    
	//    protected void printMenu(WebContext ctx, String which, String menu, String title, String key) {

    /* print a user table without any extra help in it */
    public void printUserTable(WebContext ctx) {
        printUserTableWithHelp(ctx, null, null);
    }

    public void printUserTableWithHelp(WebContext ctx, String helpLink) {
        printUserTableWithHelp(ctx, helpLink, null);
    }
    
    public void printUserTableWithHelp(WebContext ctx, String helpLink, String helpName) {
    	ctx.put("helpLink",helpLink);
    	ctx.put("helpName",helpName);
    	ctx.includeFragment("usermenu.jsp");    	
    }
    
    public static final String REDO_FIELD_LIST[] = {
    	QUERY_LOCALE, QUERY_SECTION, QUERY_DO, "forum"
    };
    /**
    * Handle creating a new user
    */
    public void doNew(WebContext ctx) {
        printHeader(ctx, "New User");
        printUserTableWithHelp(ctx, "/AddModifyUser");
        if(UserRegistry.userCanCreateUsers(ctx.session.user)) {
        	showAddUser(ctx);
        }
        ctx.println("<a href='" + ctx.url() + "'><b>SurveyTool main</b></a><hr>");
        
        String new_name = ctx.field("new_name");
        String new_email = ctx.field("new_email");
        String new_locales = ctx.field("new_locales");
        String new_org = ctx.field("new_org");
        int new_userlevel = ctx.fieldInt("new_userlevel",-1);

        
        if(!UserRegistry.userCreateOtherOrgs(ctx.session.user)) {
            new_org = ctx.session.user.org; // if not admin, must create user in the same org
        }
        
        boolean newOrgOk = false;
        try {
        	VoteResolver.Organization newOrgEnum = VoteResolver.Organization.fromString(new_org);
        	newOrgOk = true;
        } catch(IllegalArgumentException iae) {
        	newOrgOk = false;
        }
        
        if((new_name == null) || (new_name.length()<=0)) {
            ctx.println("<div class='sterrmsg'>"+ctx.iconHtml("stop","Could not add user")+"Please fill in a name.. hit the Back button and try again.</div>");
        } else if((new_email == null) ||
                  (new_email.length()<=0) ||
                  ((-1==new_email.indexOf('@'))||(-1==new_email.indexOf('.')))  ) {
            ctx.println("<div class='sterrmsg'>"+ctx.iconHtml("stop","Could not add user")+"Please fill in an <b>email</b>.. hit the Back button and try again.</div>");
        } else if(newOrgOk == false) {
        	ctx.println("<div class='sterrmsg'>"+ctx.iconHtml("stop","Could not add user")+"That Organization (<b>"+new_org+"</b>) is not valid. Either it is not spelled properly, or someone must update VoteResolver.Organization in VoteResolver.java</div>");
        } else if((new_org == null) || (new_org.length()<=0)) { // for ADMIN
            ctx.println("<div class='sterrmsg'>"+ctx.iconHtml("stop","Could not add user")+"Please fill in an <b>Organization</b>.. hit the Back button and try again.</div>");
        } else if(new_userlevel<0) {
            ctx.println("<div class='sterrmsg'>"+ctx.iconHtml("stop","Could not add user")+"Please fill in a <b>user level</b>.. hit the Back button and try again.</div>");
        } else if(new_userlevel==UserRegistry.EXPERT && ctx.session.user.userlevel!=UserRegistry.ADMIN) {
            ctx.println("<div class='sterrmsg'>"+ctx.iconHtml("stop","Could not add user")+"Only Admin can create EXPERT users.. hit the Back button and try again.</div>");
        } else {
            UserRegistry.User u = reg.getEmptyUser();
            
            u.name = new_name;
            u.userlevel = UserRegistry.userCanCreateUserOfLevel(ctx.session.user, new_userlevel);
            u.email = new_email;
            u.org = new_org;
            u.locales = new_locales;
            u.password = UserRegistry.makePassword(u.email+u.org+ctx.session.user.email);
            
            UserRegistry.User registeredUser = reg.newUser(ctx, u);
            
            if(registeredUser == null) {
                if(reg.get(new_email) != null) { // already exists..
                    ctx.println("<div class='sterrmsg'>"+ctx.iconHtml("stop","Could not add user")+"A user with that email already exists. If you have permission, you may be able to edit this user: <tt>");
                    printUserZoomLink(ctx,new_email,new_email);
                    ctx.println("</tt> </div>");                
                } else {
                    ctx.println("<div class='sterrmsg'>"+ctx.iconHtml("stop","Could not add user")+"Couldn't add user <tt>" + new_email + "</tt> - an unknown error occured.</div>");
                }
            } else {
                ctx.println("<i>"+ctx.iconHtml("okay","added")+"user added.</i>");
                registeredUser.printPasswordLink(ctx);
                WebContext nuCtx = (WebContext)ctx.clone();
                nuCtx.addQuery(QUERY_DO,"list");
                nuCtx.addQuery(LIST_JUST, changeAtTo40(new_email));
                ctx.println("<p>"+ctx.iconHtml("warn","Note..")+"The password is not sent to the user automatically. You can do so in the '<b><a href='"+nuCtx.url()+"#u_"+u.email+"'>"+ctx.iconHtml("zoom","Zoom in on user")+"manage "+new_name+"</a></b>' page.</p>");
            }
        }
        
        printFooter(ctx);
    }
    
    
    public static void showCoverageLanguage(WebContext ctx, String group, String lang) {
        ctx.print("<tt style='border: 1px solid gray; margin: 1px; padding: 1px;' class='codebox'>"+lang+"</tt> ("+new ULocale(lang).getDisplayName(ctx.displayLocale)+":<i>"+group+"</i>)</tt>" );
    }
    
    public void showAddUser(WebContext ctx) {
    	reg.setOrgList(); // setup the list of orgs
        String defaultorg = "";
        
        if(!UserRegistry.userIsAdmin(ctx.session.user)) {
        	defaultorg = URLEncoder.encode(ctx.session.user.org);
        }

        ctx.println("<a href='" + ctx.jspLink("adduser.jsp") 
                + "&amp;defaultorg="+defaultorg
                +"'>Add User</a> |");
    }
    
    public void doCoverage(WebContext ctx) {
        boolean showCodes = false; //ctx.prefBool(PREF_SHOWCODES);
        printHeader(ctx, "Locale Coverage");

        if(!UserRegistry.userIsVetter(ctx.session.user)) {
            ctx.print("Not authorized.");
        }
        
        
        printUserTableWithHelp(ctx, "/LocaleCoverage");

        showAddUser(ctx);

//        ctx.println("<a href='" + ctx.url()+ctx.urlConnector()+"do=list'>[List Users]</a>");
        ctx.print("<br>");
        ctx.println("<a href='" + ctx.url() + "'><b>SurveyTool in</b></a><hr>");
        String org = ctx.session.user.org;
        if(UserRegistry.userCreateOtherOrgs(ctx.session.user)) {
            org = null; // all
        }
        
        StandardCodes sc = StandardCodes.make();
        
        LocaleTree tree = getLocaleTree();

        WebContext subCtx = (WebContext)ctx.clone();
        subCtx.setQuery(QUERY_DO,"coverage");
        boolean participation = showTogglePref(subCtx, "cov_participation", "Participation Shown (click to toggle)");
        String missingLocalesForOrg = org;
        if(missingLocalesForOrg == null) {
            missingLocalesForOrg = showListPref(subCtx,PREF_COVTYP, "Coverage Type", ctx.getLocaleCoverageOrganizations(), true);
        }
        if(missingLocalesForOrg == null || missingLocalesForOrg.length()==0 || missingLocalesForOrg.equals("default")) {
            missingLocalesForOrg = "default"; // ?!
        }
        
        ctx.println("<h4>Showing coverage for: " + org + "</h4>");
        if(missingLocalesForOrg != org) {
            ctx.println("<h4> (and missing locales for " + missingLocalesForOrg +")</h4>");
        }
        
        File inFiles[] = getInFiles();
        int nrInFiles = inFiles.length;
        //String localeList[] = new String[nrInFiles];
        Set<CLDRLocale> allLocs = this.getLocalesSet();
        /*
        for(int i=0;i<nrInFiles;i++) {
            String localeName = inFiles[i].getName();
            int dot = localeName.indexOf('.');
            if(dot !=  -1) {
                localeName = localeName.substring(0,dot);
            }
            localeList[i]=localeName;
			allLocs.add(localeName);
        }
*/
        int totalUsers = 0;
        int allUsers = 0; // users with all
        
        int totalSubmit=0;
        int totalVet=0;
        
        Map<CLDRLocale,Set<CLDRLocale>> intGroups = getIntGroups();
		
        Connection conn = null;
        Map<String, String> userMap = null;
        Map<String, String> nullMap = null;
        Hashtable<CLDRLocale,Hashtable<Integer,String>> localeStatus = null;
        Hashtable<CLDRLocale,Hashtable<Integer,String>> nullStatus = null;
        
        {
            userMap = new TreeMap<String, String>();
            nullMap = new TreeMap<String, String>();
            localeStatus = new Hashtable<CLDRLocale,Hashtable<Integer,String>>();
            nullStatus = new Hashtable<CLDRLocale,Hashtable<Integer,String>>();
        }
        
        Set<CLDRLocale> s = new TreeSet<CLDRLocale>();
        Set<CLDRLocale> badSet = new TreeSet<CLDRLocale>();
		PreparedStatement psMySubmit = null;
		PreparedStatement psMyVet = null;
		PreparedStatement psnSubmit = null;
		PreparedStatement psnVet = null;

        try {
            conn = dbUtils.getDBConnection();
			 psMySubmit = conn.prepareStatement("select COUNT(id) from cldr_data where submitter=?",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
			psMyVet = conn.prepareStatement("select COUNT(id) from cldr_vet where submitter=?",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
			psnSubmit = conn.prepareStatement("select COUNT(id) from cldr_data where submitter=? and locale=?",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
			psnVet = conn.prepareStatement("select COUNT(id) from cldr_vet where submitter=? and locale=?",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);

			synchronized(reg) {
            java.sql.ResultSet rs = reg.list(org,conn);
            if(rs == null) {
                ctx.println("<i>No results...</i>");
                return;
            }
            if(UserRegistry.userCreateOtherOrgs(ctx.session.user)) {
                org = "ALL"; // all
            }
            while(rs.next()) {
              //  n++;
                int theirId = rs.getInt(1);
                int theirLevel = rs.getInt(2);
                String theirName = DBUtils.getStringUTF8(rs, 3);//rs.getString(3);
                String theirEmail = rs.getString(4);
                String theirOrg = rs.getString(5);
                String theirLocaleList = rs.getString(6);
                
				String nameLink =  "<a href='"+ctx.url()+ctx.urlConnector()+"do=list&"+LIST_JUST+"="+changeAtTo40(theirEmail)+
                        "' title='More on this user...'>"+theirName +" </a>";
				// setup
			
				
                if(participation && (conn!=null)) {
					psMySubmit.setInt(1,theirId);
					psMyVet.setInt(1,theirId);
					psnSubmit.setInt(1,theirId);
					psnVet.setInt(1,theirId);
					
                   int mySubmit=DBUtils.sqlCount(ctx,conn,psMySubmit);
                    int myVet=DBUtils.sqlCount(ctx,conn,psMyVet);
                
                    String userInfo = "<tr><td>"+nameLink + "</td><td>" +"submits: "+ mySubmit+"</td><td>votes: "+myVet+"</td></tr>";
					if((mySubmit+myVet)==0) {
						nullMap.put(theirName, userInfo);
//						userInfo = "<span class='disabledbox' style='color:#888; border: 1px dashed red;'>" + userInfo + "</span>";
					} else {
						userMap.put(theirName, userInfo);
					}
                
                    totalSubmit+= mySubmit;
                    totalVet+= myVet;
                }
//                String theirIntLocs = rs.getString(7);
//timestamp(8)
                if((theirLevel > 10)||(theirLevel <= 1)) {
                    continue;
                }
                totalUsers++;

//                CookieSession theUser = CookieSession.retrieveUserWithoutTouch(theirEmail);
//                    ctx.println("   <td>" + UserRegistry.prettyPrintLocale(null) + "</td> ");
//                    ctx.println("    <td>" + UserRegistry.prettyPrintLocale(theirLocales) + "</td>");
                if((theirLocaleList == null) || 
                    theirLocaleList.length()==0) {
                    allUsers++;
                    continue;
                }
                String theirLocales[] = UserRegistry.tokenizeLocale(theirLocaleList);
                if((theirLocales==null)||(theirLocales.length==0)) {
                    // all.
                    allUsers++;
                } else {
//                    int hitList[] = new int[theirLocales.length]; // # of times each is used
					Set<CLDRLocale> theirSet = new HashSet<CLDRLocale>(); // set of locales this vetter has access to
					for(int j=0;j<theirLocales.length;j++) { 
						Set<CLDRLocale> subSet = intGroups.get(CLDRLocale.getInstance(theirLocales[j])); // Is it an interest group? (de, fr, ..)
						if(subSet!=null) {
							theirSet.addAll(subSet); // add all sublocs
						} else if(allLocs.contains(theirLocales[j])) {
							theirSet.add(CLDRLocale.getInstance(theirLocales[j]));
						} else {
							badSet.add(CLDRLocale.getInstance(theirLocales[j]));
						}
					}
					for(CLDRLocale theLocale : theirSet) {
						s.add(theLocale);
                          //      hitList[j]++;
                               // ctx.println("user " + theirEmail + " with " + theirLocales[j] + " covers " + theLocale + "<br>");
                       Hashtable<CLDRLocale,Hashtable<Integer,String>> theHash = localeStatus; // to the 'status' field
                       String userInfo = nameLink+" ";
					   if(participation && conn != null) {
							psnSubmit.setString(2,theLocale.getBaseName());
							psnVet.setString(2,theLocale.getBaseName());
							
							int nSubmit=DBUtils.sqlCount(ctx,conn,psnSubmit);
							int nVet=DBUtils.sqlCount(ctx,conn,psnVet);
							
							if((nSubmit+nVet)==0) {
								theHash = nullStatus; // vetter w/ no work done
							}
                        
							if(nSubmit>0) {
								userInfo = userInfo + " submits: "+ nSubmit+" ";
							}
							if(nVet > 0) {
								userInfo = userInfo + " votes: "+nVet;
							}
							
//							if((nSubmit+nVet)==0) {
//										userInfo = "<span class='disabledbox' style='color:#888; border: 1px dashed red;'>" + userInfo + "</span>";
//								userInfo = "<strike>"+userInfo+"</strike>";
//							}
//									userInfo = userInfo + ", file: "+theLocale+", th: " + theirLocales[j];
                        }
                        Hashtable<Integer,String> oldStr = theHash.get(theLocale);
                        
                        
                        if(oldStr==null) {
                            oldStr = new Hashtable<Integer, String>();
                            theHash.put(theLocale,oldStr);
                        } else {
                        //     // oldStr = oldStr+"<br>\n";
                        }

                        oldStr.put(new Integer(theirId), userInfo + "<!-- " + theLocale + " -->");
					   
                    }
					/*
                    for(int j=0;j<theirLocales.length;j++) {
                        if(hitList[j]==0) {
                            badSet.add(theirLocales[j]);
                        }
                    }*/
                }
            }
            // #level $name $email $org
            rs.close();
        }/*end synchronized(reg)*/ } catch(SQLException se) {
            SurveyLog.logger.log(java.util.logging.Level.WARNING,"Query for org " + org + " failed: " + DBUtils.unchainSqlException(se),se);
            ctx.println("<i>Failure: " + DBUtils.unchainSqlException(se) + "</i><br>");
        } finally {
    		DBUtils.close(psMySubmit,psMyVet,psnSubmit,psnVet,conn);
        }

//        Map<String, CLDRLocale> lm = getLocaleListMap();
//        if(lm == null) {
//            busted("Can't load CLDR data files from " + fileBase);
//            throw new RuntimeException("Can't load CLDR data files from " + fileBase);
//        }
        
        if(!badSet.isEmpty()) {
            ctx.println("<B>Locales not in CLDR but assigned to vetters:</b> <br>");
            int n=0;
            boolean first=true;
            Set<CLDRLocale> subSet = new TreeSet<CLDRLocale>();
            for(CLDRLocale li : badSet) {
            	if(li.toString().indexOf('_')>=0) {
            		n++;
            		subSet.add(li);
           			continue;
            	}
                if(first==false) {
                    ctx.print(" ");
                } else {
                    first = false;
                }
                ctx.print("<tt style='border: 1px solid gray; margin: 1px; padding: 1px;' class='codebox'>"+li.toString()+"</tt>" );
            }
            ctx.println("<br>");
            if(n>0) {
            	ctx.println("Note: "+n+" locale(s) were specified that were sublocales. This is no longer supported, specify the top level locale (en, not en_US or en_Shaw): <br><font size=-1>");
            	if(isUnofficial) for(CLDRLocale li:subSet) {
                    ctx.print(" <tt style='border: 1px solid gray; margin: 1px; padding: 1px;' class='codebox'><font size=-1>"+li.toString()+"</font></tt>" );
            	}
            	ctx.println("</font><br>");
            }
        }
        
        // Now, calculate coverage of requested locales for this organization
        //sc.getGroup(locale,missingLocalesForOrg);
        Set<CLDRLocale> languagesNotInCLDR = new TreeSet<CLDRLocale>();
        Set<CLDRLocale> languagesMissing = new HashSet<CLDRLocale>();
        Set<CLDRLocale> allLanguages = new TreeSet<CLDRLocale>();
        {
        	for(String code : sc.getAvailableCodes("language")) {
        		allLanguages.add(CLDRLocale.getInstance(code));
        	}
        }
        for(Iterator<CLDRLocale> li = allLanguages.iterator();li.hasNext();) {
        	CLDRLocale lang = (CLDRLocale)(li.next());
            String group = sc.getGroup(lang.getBaseName(), missingLocalesForOrg);
            if((group != null) &&
                // Not sure why we did this... jce (!"basic".equals(group)) && // exclude it for being basic
                (null==supplemental.defaultContentToParent(group)) ) {
                //SurveyLog.logger.warning("getGroup("+lang+", " + missingLocalesForOrg + ") = " + group);
                if(!isValidLocale(lang)) {
                    //SurveyLog.logger.warning("-> not in lm: " + lang);
                    languagesNotInCLDR.add(lang);
                } else {
                    if(!s.contains(lang)) {
                     //   SurveyLog.logger.warning("-> not in S: " + lang);
                        languagesMissing.add(lang);
                    } else {
                       // SurveyLog.logger.warning("in lm && s: " + lang);
                    }
                }
            }
        }

        if(!languagesNotInCLDR.isEmpty()) {
            ctx.println("<p class='hang'>"+ctx.iconHtml("stop","locales missing from CLDR")+"<B>Required by " + missingLocalesForOrg + " but not in CLDR: </b>");
            boolean first=true;
            for(Iterator<CLDRLocale> li = languagesNotInCLDR.iterator();li.hasNext();) {
                if(first==false) {
                    ctx.print(", ");
                } else {
                    first = false;
                }
                String lang = li.next().toString();
                showCoverageLanguage(ctx,sc.getGroup(lang, missingLocalesForOrg), lang);
            }
            ctx.println("<br>");
			ctx.printHelpLink("/LocaleMissing","Locale is missing from CLDR");
            ctx.println("</p><br>");
        }

        if(!languagesMissing.isEmpty()) {
            ctx.println("<p class='hang'>"+ctx.iconHtml("stop","locales without vetters")+
                "<B>Required by " + missingLocalesForOrg + " but no vetters: </b>");
            boolean first=true;
            for(Iterator<CLDRLocale> li = languagesMissing.iterator();li.hasNext();) {
                if(first==false) {
                    ctx.print(", ");
                } else {
                    first = false;
                }
                String lang = li.next().toString();
                showCoverageLanguage(ctx, sc.getGroup(lang, missingLocalesForOrg), lang);
            }
            ctx.println("<br>");
			//ctx.printHelpLink("/LocaleMissing","Locale is missing from CLDR");
            ctx.println("</p><br>");            
        }


        ctx.println("Locales in <b>bold</b> have assigned vetters.<br><table summary='Locale Coverage' border=1 class='list'>");
        int n=0;
        for(String ln:tree.getTopLocales()) {
            n++;
            CLDRLocale aLocale = tree.getLocaleCode(ln);
            ctx.print("<tr class='row" + (n%2) + "'>");
            ctx.print(" <td valign='top'>");
            boolean has = (s.contains(aLocale));
            if(has) {
                ctx.print("<span class='selected'>");
            } else {
                ctx.print("<span class='disabledbox' style='color:#888'>");
            }
//            ctx.print(aLocale);            
            //ctx.print("<br><font size='-1'>"+new ULocale(aLocale).getDisplayName()+"</font>");
			printLocaleStatus(ctx, aLocale, ln.toString(), aLocale.toString());
            ctx.print("</span>");
            if(languagesMissing.contains(aLocale)) {
                ctx.println("<br>"+ctx.iconHtml("stop","No " + missingLocalesForOrg + " vetters")+ "<i>(coverage: "+ sc.getGroup(aLocale.toString(), missingLocalesForOrg)+")</i>");
            }

            if(showCodes) {
                ctx.println("<br><tt>" + aLocale + "</tt>");
            }
			if(localeStatus!=null && !localeStatus.isEmpty()) {
				Hashtable<Integer,String> what = localeStatus.get(aLocale);
				if(what!=null) {
					ctx.println("<ul>");
					for(Iterator<String> i = what.values().iterator();i.hasNext();) {
						ctx.println("<li>"+i.next()+"</li>");
					}
					ctx.println("</ul>");
				}
			}
            boolean localeIsDefaultContent = (null!=supplemental.defaultContentToParent(aLocale.toString()));
			if(localeIsDefaultContent) {
                        ctx.println(" (<i>default content</i>)");
            } else if(participation && nullStatus!=null && !nullStatus.isEmpty()) {
				Hashtable<Integer, String> what = nullStatus.get(aLocale);				
				if(what!=null) {
					ctx.println("<br><blockquote> <b>Did not participate:</b> ");
					for(Iterator<String> i = what.values().iterator();i.hasNext();) {
						ctx.println(i.next().toString()	);
						if(i.hasNext()) {
							ctx.println(", ");
						}
					}
					ctx.println("</blockquote>");
				}
			}
            ctx.println(" </td>");
            
            Map<String,CLDRLocale> sm = tree.getSubLocales(aLocale);  // sub locales 
            
            ctx.println("<td valign='top'>");
            int j = 0;
            for(Iterator<String> si = sm.keySet().iterator();si.hasNext();) {
                String sn = si.next().toString();
                CLDRLocale subLocale = sm.get(sn);
               // if(subLocale.length()>0) {

					has = (s.contains(subLocale));

					if(j>0) { 
						if(localeStatus==null) {
							ctx.println(", ");
						} else {
							ctx.println("<br>");
						}
					}

                    if(has) {
                        ctx.print("<span class='selected'>");
                    } else {
                        ctx.print("<span class='disabledbox' style='color:#888'>");
                    }
                  //  ctx.print(subLocale);           
//                    ctx.print("&nbsp;<font size='-1'>("+new ULocale(subLocale).getDisplayName()+")</font>");
    
					printLocaleStatus(ctx, CLDRLocale.getInstance(subLocale.toString()), sn, subLocale.toString());
                   // if(has) {
                        ctx.print("</span>");
                   // }


//                    printLocaleLink(baseContext, subLocale, sn);
                    if(showCodes) {
                        ctx.println("&nbsp;-&nbsp;<tt>" + subLocale + "</tt>");
                    }
                    boolean isDc = (null!=supplemental.defaultContentToParent(subLocale.toString()));
                    
					if(localeStatus!=null&&!nullStatus.isEmpty()) {
						Hashtable<Integer, String> what = localeStatus.get(subLocale);
						if(what!=null) {
							ctx.println("<ul>");
							for(Iterator<String> i = what.values().iterator();i.hasNext();) {
								ctx.println("<li>"+i.next()+"</li>");
							}
							ctx.println("</ul>");
						}
					}
                    if(isDc) {
                        ctx.println(" (<i>default content</i>)");
                    } // else if(participation && nullStatus!=null && !nullStatus.isEmpty()) {
//						Hashtable<Integer, String> what = nullStatus.get(subLocale);				
//						if(what!=null) {
//							ctx.println("<br><blockquote><b>Did not participate:</b> ");
//							for(Iterator<String> i = what.values().iterator();i.hasNext();) {
//								ctx.println(i.next().toString()	);
//								if(i.hasNext()) {
//									ctx.println(", ");
//								}
//							}
//							ctx.println("</blockquote>");
//						}
//					}
                //}
                j++;
            }
            ctx.println("</td>");
            ctx.println("</tr>");
        }
        ctx.println("</table> ");
        ctx.println(totalUsers + "  users, including " + allUsers + " with 'all' privs (not counted against the locale list)<br>");
    
        if(conn!=null) {
            if(participation) {
                ctx.println("Selected users have submitted " + totalSubmit + " items, and voted for " + totalVet + " items (including implied votes).<br>");
            }
            //int totalResult=sqlCount(ctx,conn,"select COUNT(*) from CLDR_RESULT");
            //int totalData=sqlCount(ctx,conn,"select COUNT(id) from CLDR_DATA");
            //ctx.println("In all, the SurveyTool has " + totalResult + " items.<br>");
			
            if(participation) {
                ctx.println("<hr>");
                ctx.println("<h4>Participated: "+userMap.size()+"</h4><table border='1'>");
                for(Iterator<String> i = userMap.values().iterator();i.hasNext();) {
                    String which = (String)i.next();
                    ctx.println(which);
                }
                ctx.println("</table><h4>Did Not Participate at all: "+nullMap.size()+"</h4><table border='1'>");
                for(Iterator<String> i = nullMap.values().iterator();i.hasNext();) {
                    String which = (String)i.next();
                    ctx.println(which);
                }
                ctx.println("</table>");
            }
            DBUtils.closeDBConnection(conn);
		}

        printFooter(ctx);
    }
    
    // ============= User list management
    static final String LIST_ACTION_SETLEVEL = "set_userlevel_";
    static final String LIST_ACTION_NONE = "-";
    static final String LIST_ACTION_SHOW_PASSWORD = "showpassword_";
    static final String LIST_ACTION_SEND_PASSWORD = "sendpassword_";
    static final String LIST_ACTION_SETLOCALES = "set_locales_";
    static final String LIST_ACTION_DELETE0 = "delete0_";
    static final String LIST_ACTION_DELETE1 = "delete_";
    static final String LIST_JUST = "justu";
    static final String LIST_MAILUSER = "mailthem";
    static final String LIST_MAILUSER_WHAT = "mailthem_t";
    static final String LIST_MAILUSER_CONFIRM = "mailthem_c";

	public static final String PREF_SHCOVERAGE = "showcov";
    
    public static final String changeAtTo40(String s) {
        return s.replaceAll("@","%40");
    }
    
    public static final String change40ToAt(String s) {
        return s.replaceAll("%40","@");
    }

    public void doUDump(WebContext ctx) {
        ctx.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
//        ctx.println("<!DOCTYPE ldml SYSTEM \"http://.../.../stusers.dtd\">");
        ctx.println("<users host=\""+ctx.serverHostport()+"\">");
        String org = null;
        Connection conn = null;
        try { 
        	conn = dbUtils.getDBConnection();
        	synchronized(reg) {
            java.sql.ResultSet rs = reg.list(org,conn);
            if(rs == null) {
                ctx.println("\t<!-- No results -->");
                return;
            }
            while(rs.next()) {
                int theirId = rs.getInt(1);
                int theirLevel = rs.getInt(2);
                String theirName = DBUtils.getStringUTF8(rs, 3);//rs.getString(3);
                String theirEmail = rs.getString(4);
                String theirOrg = rs.getString(5);
                String theirLocales = rs.getString(6);
                
                ctx.println("\t<user id=\""+theirId+"\" email=\""+theirEmail+"\">");
                ctx.println("\t\t<level n=\""+theirLevel+"\" type=\""+UserRegistry.levelAsStr(theirLevel)+"\"/>");
                ctx.println("\t\t<name>"+theirName+"</name>");
                ctx.println("\t\t<org>"+theirOrg+"</org>");
                ctx.println("\t\t<locales type=\"edit\">");
                String theirLocalesList[] = UserRegistry.tokenizeLocale(theirLocales);
                for(int i=0;i<theirLocalesList.length;i++) {
                    ctx.println("\t\t\t<locale id=\""+theirLocalesList[i]+"\"/>");
                }
                ctx.println("\t\t</locales>");
                ctx.println("\t</user>");
            }            
        }/*end synchronized(reg)*/ } catch(SQLException se) {
            SurveyLog.logger.log(java.util.logging.Level.WARNING,"Query for org " + org + " failed: " + DBUtils.unchainSqlException(se),se);
            ctx.println("<!-- Failure: " + DBUtils.unchainSqlException(se) + " -->");
        } finally {
    		DBUtils.close(conn);
        }
        ctx.println("</users>");
    }
    
    /**
     * List Users
     * @param ctx
     */
    public void doList(WebContext ctx) {
        int n=0;
        String just = ctx.field(LIST_JUST);
        String doWhat = ctx.field(QUERY_DO);
        boolean justme = false; // "my account" mode
        String listName = "list";
        if(just.length()==0) {
            just = null;
        } else {
            just = change40ToAt(just);
            justme = ctx.session.user.email.equals(just);
        }
        if(doWhat.equals("listu")) {
            listName = "listu";
            just = ctx.session.user.email;
            justme = true;
        }
        WebContext subCtx = new WebContext(ctx);
        subCtx.setQuery(QUERY_DO, doWhat);
        if(justme) {
            printHeader(ctx, "My Account");
        } else {
            printHeader(ctx, "List Users" + ((just==null)?"":(" - " + just)));
        }

        printUserTableWithHelp(ctx, "/AddModifyUser");
        ctx.print(" | ");
        printMenu(ctx, doWhat, "coverage", "Show Vetting Participation", QUERY_DO);                    
        
        if(reg.userIsTC(ctx.session.user)) {
    		ctx.println("| <a class='notselected' href='"+ctx.jspUrl("tc-emaillist.jsp"  ) +"'>Email Address of Users Who Participated</a>");
        	ctx.print(" | ");
        }
        
        if(reg.userCanCreateUsers(ctx.session.user)) {
            showAddUser(ctx);
//            ctx.println("<a href='" + ctx.jspLink("adduser.jsp") +"'>[Add User]</a> |");
        }
//        ctx.println("<a href='" + ctx.url()+ctx.urlConnector()+"do=coverage'>[Locale Coverage Reports]</a>");
        ctx.print("<br>");
        ctx.println("<a href='" + ctx.url() + "'><b>SurveyTool main</b></a><hr>");
        String org = ctx.session.user.org;
        if(just!=null) {
            ctx.println("<a href='"+ctx.url()+ctx.urlConnector()+"do=list'>\u22d6 Show all users</a><br>");
        }
        if(UserRegistry.userCreateOtherOrgs(ctx.session.user)) {
            org = null; // all
        }
        String cleanEmail = null;
        String sendWhat = ctx.field(LIST_MAILUSER_WHAT);
        boolean areSendingMail = false;
		boolean didConfirmMail = false;
		boolean showLocked = ctx.prefBool(PREF_SHOWLOCKED);
		boolean areSendingDisp = (ctx.field(LIST_MAILUSER+"_d").length())>0; // sending a dispute note?
        String mailBody = null;
        String mailSubj = null;
        boolean hideUserList = false;
        if(UserRegistry.userCanEmailUsers(ctx.session.user)) {
            cleanEmail = ctx.session.user.email;
            if(cleanEmail.equals("admin@")) {
                cleanEmail = "surveytool@unicode.org";
            }
            if(ctx.field(LIST_MAILUSER_CONFIRM).equals(cleanEmail)) {
                ctx.println("<h1>sending mail to users...</h4>");
				didConfirmMail=true;
                mailBody = "Message from " + getRequester(ctx) + ":\n--------\n"+sendWhat+
                    "\n--------\n\nSurvey Tool: http://" + ctx.serverHostport() + ctx.base()+"\n\n";
                mailSubj = "CLDR SurveyTool message from " +getRequester(ctx);
				if(!areSendingDisp) {
					areSendingMail= true; // we are ready to go ahead and mail..
				}
            } else if(ctx.hasField(LIST_MAILUSER_CONFIRM)) {
                ctx.println("<h1 class='ferrbox'>"+ctx.iconHtml("stop","emails did not match")+" not sending mail - you did not confirm the email address. See form at bottom of page."+"</h1>");
            }
            
            if(!areSendingMail && !areSendingDisp && ctx.hasField(LIST_MAILUSER)) {
                hideUserList = true; // hide the user list temporarily.
            }
        }
        Connection conn = null;
		try {
			conn = dbUtils.getDBConnection();
			synchronized (reg) {
				java.sql.ResultSet rs = reg.list(org, conn);
				if (rs == null) {
					ctx.println("<i>No results...</i>");
					return;
				}
				if (UserRegistry.userCreateOtherOrgs(ctx.session.user)) {
					org = "ALL"; // all
				}
				if (justme) {
					ctx.println("<h2>My Account</h2>");
				} else {
					ctx.println("<h2>Users for " + org + "</h2>");
					if (UserRegistry.userIsTC(ctx.session.user)) {
						showTogglePref(subCtx, PREF_SHOWLOCKED,
								"Show locked users:");
					}
					ctx.println("<br>");
					if (UserRegistry.userCanModifyUsers(ctx.session.user)) {
						ctx.println("<div class='fnotebox'>"
								+ ctx.iconHtml("warn", "warning")
								+ "Changing user level or locales while a user is active will result in  "
								+ " destruction of their session. Check if they have been working recently.</div>");
					}
				}
				// Preset box
				boolean preFormed = false;

				if (hideUserList) {
					String warnHash = "userlist";
					ctx.println("<div id='h_"
							+ warnHash
							+ "'><a href='javascript:show(\""
							+ warnHash
							+ "\")'>"
							+ "<b>+</b> Click here to show the user list...</a></div>");
					ctx.println("<!-- <noscript>Warning: </noscript> -->"
							+ "<div style='display: none' id='" + warnHash
							+ "'>");
					ctx.println("<a href='javascript:hide(\"" + warnHash
							+ "\")'>" + "(<b>- hide userlist</b>)</a><br>");

				}

				if ((just == null)
						&& UserRegistry.userCanModifyUsers(ctx.session.user)
						&& !justme) {
					ctx.println("<div class='pager' style='align: right; float: right; margin-left: 4px;'>");
					ctx.println("<form method=POST action='" + ctx.base()
							+ "'>");
					ctx.printUrlAsHiddenFields();
					ctx.println("Set menus:<br><label>all ");
					ctx.println("<select name='preset_from'>");
					ctx.println("   <option>" + LIST_ACTION_NONE + "</option>");
					for (int i = 0; i < UserRegistry.ALL_LEVELS.length; i++) {
						ctx.println("<option class='user"
								+ UserRegistry.ALL_LEVELS[i] + "' ");
						ctx.println(" value='"
								+ UserRegistry.ALL_LEVELS[i]
								+ "'>"
								+ UserRegistry.levelToStr(ctx,
										UserRegistry.ALL_LEVELS[i])
								+ "</option>");
					}
					ctx.println("</select></label> <br>");
					ctx.println(" <label>to");
					ctx.println("<select name='preset_do'>");
					ctx.println("   <option>" + LIST_ACTION_NONE + "</option>");
					/*
					 * for(int i=0;i<UserRegistry.ALL_LEVELS.length;i++) {
					 * ctx.println("<option class='user" +
					 * UserRegistry.ALL_LEVELS[i] + "' ");
					 * ctx.println(" value='"
					 * +LIST_ACTION_SETLEVEL+UserRegistry.ALL_LEVELS[i]+"'>" +
					 * UserRegistry.levelToStr(ctx, UserRegistry.ALL_LEVELS[i])
					 * + "</option>"); } ctx.println("   <option>" +
					 * LIST_ACTION_NONE + "</option>");
					 * ctx.println("   <option value='" + LIST_ACTION_DELETE0
					 * +"'>Delete user..</option>"); ctx.println("   <option>" +
					 * LIST_ACTION_NONE + "</option>");
					 */
					ctx.println("   <option value='"
							+ LIST_ACTION_SHOW_PASSWORD
							+ "'>Show password URL...</option>");
					ctx.println("   <option value='"
							+ LIST_ACTION_SEND_PASSWORD
							+ "'>Resend password...</option>");
					// ctx.println("   <option value='" + LIST_ACTION_SETLOCALES
					// + "'>Set locales...</option>");
					ctx.println("</select></label> <br>");
					if (just != null) {
						ctx.print("<input type='hidden' name='" + LIST_JUST
								+ "' value='" + just + "'>");
					}
					ctx.println("<input type='submit' name='do' value='"
							+ listName + "'></form>");
					if ((ctx.field("preset_from").length() > 0)
							&& !ctx.field("preset_from").equals(
									LIST_ACTION_NONE)) {
						ctx.println("<hr><i><b>Menus have been pre-filled. <br> Confirm your choices and click Change.</b></i>");
						ctx.println("<form method=POST action='" + ctx.base()
								+ "'>");
						ctx.println("<input type='submit' name='doBtn' value='Change'>");
						preFormed = true;
					}
					ctx.println("</div>");
				}
				int preset_fromint = ctx.fieldInt("preset_from", -1);
				String preset_do = ctx.field("preset_do");
				if (preset_do.equals(LIST_ACTION_NONE)) {
					preset_do = "nothing";
				}
				if (/* (just==null)&& */((UserRegistry
						.userCanModifyUsers(ctx.session.user))) && !preFormed) { // form
																					// was
																					// already
																					// started,
																					// above
					ctx.println("<form method=POST action='" + ctx.base()
							+ "'>");
				}
				if (just != null) {
					ctx.print("<input type='hidden' name='" + LIST_JUST
							+ "' value='" + just + "'>");
				}
				if (justme || UserRegistry.userCanModifyUsers(ctx.session.user)) {
					ctx.printUrlAsHiddenFields();
					ctx.println("<input type='hidden' name='do' value='"
							+ listName + "'>");
					ctx.println("<input type='submit' name='doBtn' value='Do Action'>");
				}
				ctx.println("<table summary='User List' class='userlist' border='2'>");
				ctx.println(" <tr><th></th><th>Organization / Level</th><th>Name/Email</th><th>Action</th><th>Locales</th><th>Seen</th></tr>");
				String oldOrg = null;
				int locked = 0;
				while (rs.next()) {
					int theirId = rs.getInt(1);
					int theirLevel = rs.getInt(2);
					if (!showLocked && theirLevel >= UserRegistry.LOCKED) {
						locked++;
						continue;
					}
					String theirName = DBUtils.getStringUTF8(rs, 3);// rs.getString(3);
					String theirEmail = rs.getString(4);
					String theirOrg = rs.getString(5);
					String theirLocales = rs.getString(6);
					String theirIntlocs = rs.getString(7);
					java.sql.Timestamp theirLast = rs.getTimestamp(8);
					boolean havePermToChange = UserRegistry.userCanModifyUser(
							ctx.session.user, theirId, theirLevel);
					String theirTag = theirId + "_" + theirEmail; // ID+email -
																	// prevents
																	// stale
																	// data.
																	// (i.e.
																	// delete of
																	// user 3 if
																	// the rows
																	// change..)
					String action = ctx.field(theirTag);
					CookieSession theUser = CookieSession
							.retrieveUserWithoutTouch(theirEmail);

					if (just != null && !just.equals(theirEmail)) {
						continue;
					}
					n++;

					if ((just == null) && (!justme)
							&& (!theirOrg.equals(oldOrg))) {
						ctx.println("<tr class='heading' ><th class='partsection' colspan='6'><a name='"
								+ theirOrg
								+ "'><h4>"
								+ theirOrg
								+ "</h4></a></th></tr>");
						oldOrg = theirOrg;
					}

					ctx.println("  <tr class='user" + theirLevel + "'>");

					if (areSendingMail && (theirLevel < UserRegistry.LOCKED)) {
						ctx.print("<td class='framecell'>");
						mailUser(ctx, theirEmail, mailSubj, mailBody);
						ctx.println("</td>");
					}
					// first: DO.

					if (havePermToChange) { // do stuff

						String msg = null;
						if (ctx.field(LIST_ACTION_SETLOCALES + theirTag)
								.length() > 0) {
							ctx.println("<td class='framecell' >");
							String newLocales = ctx
									.field(LIST_ACTION_SETLOCALES + theirTag);
							msg = reg.setLocales(ctx, theirId, theirEmail,
									newLocales);
							ctx.println(msg);
							theirLocales = newLocales; // MODIFY
							if (theUser != null) {
								ctx.println("<br/><i>Logging out user session "
										+ theUser.id
										+ " and deleting all unsaved changes</i>");
								theUser.remove();
							}
							UserRegistry.User newThem = reg.getInfo(theirId);
							if (newThem != null) {
								theirLocales = newThem.locales; // update
							}
							ctx.println("</td>");
						} else if ((action != null) && (action.length() > 0)
								&& (!action.equals(LIST_ACTION_NONE))) { // other
																			// actions
							ctx.println("<td class='framecell'>");

							// check an explicit list. Don't allow random levels
							// to be set.
							for (int i = 0; i < UserRegistry.ALL_LEVELS.length; i++) {
								if (action.equals(LIST_ACTION_SETLEVEL
										+ UserRegistry.ALL_LEVELS[i])) {
									if ((just == null)
											&& (UserRegistry.ALL_LEVELS[i] <= UserRegistry.TC)) {
										ctx.println("<b>Must be zoomed in on a user to promote them to TC</b>");
									} else {
										msg = reg.setUserLevel(ctx, theirId,
												theirEmail,
												UserRegistry.ALL_LEVELS[i]);
										ctx.println("Set user level to "
												+ UserRegistry
														.levelToStr(
																ctx,
																UserRegistry.ALL_LEVELS[i]));
										ctx.println(": " + msg);
										theirLevel = UserRegistry.ALL_LEVELS[i];
										if (theUser != null) {
											ctx.println("<br/><i>Logging out user session "
													+ theUser.id + "</i>");
											theUser.remove();
										}
									}
								}
							}

							if (action.equals(LIST_ACTION_SHOW_PASSWORD)) {
								String pass = reg.getPassword(ctx, theirId);
								if (pass != null) {
									UserRegistry.printPasswordLink(ctx,
											theirEmail, pass);
								}
							} else if (action.equals(LIST_ACTION_SEND_PASSWORD)) {
								String pass = reg.getPassword(ctx, theirId);
								if (pass != null) {
									UserRegistry.printPasswordLink(ctx,
											theirEmail, pass);
									notifyUser(ctx, theirEmail, pass);
								}
							} else if (action.equals(LIST_ACTION_DELETE0)) {
								ctx.println("Ensure that 'confirm delete' is chosen at right and click Change again to delete..");
							} else if ((UserRegistry.userCanDeleteUser(
									ctx.session.user, theirId, theirLevel))
									&& (action.equals(LIST_ACTION_DELETE1))) {
								msg = reg.delete(ctx, theirId, theirEmail);
								ctx.println("<strong style='font-color: red'>Deleting...</strong><br>");
								ctx.println(msg);
							} else if ((UserRegistry.userCanModifyUser(
									ctx.session.user, theirId, theirLevel))
									&& (action.equals(LIST_ACTION_SETLOCALES))) {
								if (theirLocales == null) {
									theirLocales = "";
								}
								ctx.println("<label>Locales: (space separated) <input name='"
										+ LIST_ACTION_SETLOCALES
										+ theirTag
										+ "' value='"
										+ theirLocales
										+ "'></label>");
							} else if (UserRegistry.userCanDeleteUser(
									ctx.session.user, theirId, theirLevel)) {
								// change of other stuff.
								UserRegistry.InfoType type = UserRegistry.InfoType.fromAction(action);
								
								
								if (UserRegistry.userIsAdmin(ctx.session.user)
										&& type == UserRegistry.InfoType.INFO_PASSWORD) {
									String what = "password";

									String s0 = ctx.field("string0" + what);
									String s1 = ctx.field("string1" + what);
									if (s0.equals(s1) && s0.length() > 0) {
										ctx.println("<h4>Change " + what
												+ " to <tt class='codebox'>"
												+ s0 + "</tt></h4>");
										action = ""; // don't popup the menu
														// again.

										msg = reg.updateInfo(ctx, theirId,
												theirEmail, type, s0);
										ctx.println("<div class='fnotebox'>"
												+ msg + "</div>");
										ctx.println("<i>click Change again to see changes</i>");
									} else {
										ctx.println("<h4>Change " + what
												+ "</h4>");
										if (s0.length() > 0) {
											ctx.println("<p class='ferrbox'>Both fields must match.</p>");
										}
										ctx.println("<label><b>New "
												+ what
												+ ":</b><input type='password' name='string0"
												+ what + "' value='" + s0
												+ "'></label><br>");
										ctx.println("<label><b>New "
												+ what
												+ ":</b><input type='password' name='string1"
												+ what + "'> (confirm)</label>");

										ctx.println("<br><br>");
										ctx.println("(Suggested password: <tt>"
												+ UserRegistry
														.makePassword(theirEmail)
												+ "</tt> )");
									}
								} else if (type!=null) {
									String what = type.toString();

									String s0 = ctx.field("string0" + what);
									String s1 = ctx.field("string1" + what);
									if(type==InfoType.INFO_ORG) s1=s0; /* ignore */
									if (s0.equals(s1) && s0.length() > 0) {
										ctx.println("<h4>Change " + what
												+ " to <tt class='codebox'>"
												+ s0 + "</tt></h4>");
										action = ""; // don't popup the menu
														// again.

										msg = reg.updateInfo(ctx, theirId,
												theirEmail, type, s0);
										ctx.println("<div class='fnotebox'>"
												+ msg + "</div>");
										ctx.println("<i>click Change again to see changes</i>");
									} else {
										ctx.println("<h4>Change " + what
												+ "</h4>");
										if (s0.length() > 0) {
											ctx.println("<p class='ferrbox'>Both fields must match.</p>");
										}
										if(type==InfoType.INFO_ORG){
											ctx.println("<select name='string0"+what+"'>");
											ctx.println("<option value='' >Choose...</option>");
											for(String o : UserRegistry.getOrgList()) {
													ctx.print("<option value='"+o+"' ");
													if(o.equals(theirOrg)) {
															ctx.print(" selected='selected' "); 
													}
													ctx.println(">"+o+"</option>");
											}
											ctx.println("</select>");
										} else {
											ctx.println("<label><b>New " + what
													+ ":</b><input name='string0"
													+ what + "' value='" + s0
													+ "'></label><br>");
											ctx.println("<label><b>New " + what
													+ ":</b><input name='string1"
													+ what + "'> (confirm)</label>");
										}
									}
								}
							}
							// ctx.println("Change to " + action);
						} else {
							ctx.print("<td>");
						}
					} else {
						ctx.print("<td>");
					}

					if (just == null) {
						printUserZoomLink(ctx, theirEmail, "");
					}
					ctx.println("</td>");

					// org, level
					ctx.println("    <td>"
							+ theirOrg
							+ "<br>"
							+ "&nbsp; <span style='font-size: 80%' align='right'>"
							+ UserRegistry.levelToStr(ctx, theirLevel)
									.replaceAll(" ", "&nbsp;") + "</span></td>");

					ctx.println("    <td valign='top'><font size='-1'>#"
							+ theirId + " </font> <a name='u_" + theirEmail
							+ "'>" + theirName + "</a>");
					ctx.println("    <a href='mailto:" + theirEmail + "'>"
							+ theirEmail + "</a>");
					ctx.print("</td><td>");
					if (havePermToChange) {
						// Was something requested?

						{ // PRINT MENU
							ctx.print("<select name='" + theirTag + "'>");

							// set user to VETTER
							ctx.println("   <option value=''>"
									+ LIST_ACTION_NONE + "</option>");
							if (just != null) {
								for (int i = 0; i < UserRegistry.ALL_LEVELS.length; i++) {
									int lev = UserRegistry.ALL_LEVELS[i];
									if ((just == null)
											&& (lev <= UserRegistry.TC)) {
										continue; // no promotion to TC from
													// zoom out
									}
									doChangeUserOption(
											ctx,
											lev,
											theirLevel,
											false
													&& (preset_fromint == theirLevel)
													&& preset_do
															.equals(LIST_ACTION_SETLEVEL
																	+ lev));
								}
								ctx.println("   <option disabled>"
										+ LIST_ACTION_NONE + "</option>");
							}
							ctx.println("   <option ");
							if ((preset_fromint == theirLevel)
									&& preset_do
											.equals(LIST_ACTION_SHOW_PASSWORD)) {
								ctx.println(" SELECTED ");
							}
							ctx.println(" value='" + LIST_ACTION_SHOW_PASSWORD
									+ "'>Show password...</option>");
							ctx.println("   <option ");
							if ((preset_fromint == theirLevel)
									&& preset_do
											.equals(LIST_ACTION_SEND_PASSWORD)) {
								ctx.println(" SELECTED ");
							}
							ctx.println(" value='" + LIST_ACTION_SEND_PASSWORD
									+ "'>Send password...</option>");

							if (just != null) {
								if (theirLevel > UserRegistry.TC) {
									ctx.println("   <option ");
									if ((preset_fromint == theirLevel)
											&& preset_do
													.equals(LIST_ACTION_SETLOCALES)) {
										// ctx.println(" SELECTED ");
									}
									ctx.println(" value='"
											+ LIST_ACTION_SETLOCALES
											+ "'>Set locales...</option>");
								}
								if (UserRegistry.userCanDeleteUser(
										ctx.session.user, theirId, theirLevel)) {
									ctx.println("   <option>"
											+ LIST_ACTION_NONE + "</option>");
									if ((action != null)
											&& action
													.equals(LIST_ACTION_DELETE0)) {
										ctx.println("   <option value='"
												+ LIST_ACTION_DELETE1
												+ "' SELECTED>Confirm delete</option>");
									} else {
										ctx.println("   <option ");
										if ((preset_fromint == theirLevel)
												&& preset_do
														.equals(LIST_ACTION_DELETE0)) {
											// ctx.println(" SELECTED ");
										}
										ctx.println(" value='"
												+ LIST_ACTION_DELETE0
												+ "'>Delete user..</option>");
									}
								}
								if (just != null) { // only do these in 'zoomin'
													// view.
									ctx.println("   <option disabled>"
											+ LIST_ACTION_NONE + "</option>");

									InfoType current = InfoType.fromAction(action);
									for(InfoType info : InfoType.values()) {
										ctx.print(" <option ");
										if (info==current) {
											ctx.print(" SELECTED ");
										}
										ctx.println(" value='"
												+ info.toAction()
												+ "'>Change "+info.toString()+"...</option>");
									}
								}
							}
							ctx.println("    </select>");
						} // end menu
					}
					ctx.println("</td>");

					if (theirLevel <= UserRegistry.TC) {
						ctx.println(" <td>"
								+ UserRegistry.prettyPrintLocale(null)
								+ "</td> ");
					} else {
						ctx.println(" <td>"
								+ UserRegistry.prettyPrintLocale(theirLocales)
								+ "</td>");
					}

					// are they logged in?
					if ((theUser != null)
							&& UserRegistry
									.userCanModifyUsers(ctx.session.user)) {
						ctx.println("<td>");
						ctx.println("<b>active: " + timeDiff(theUser.last)
								+ " ago</b>");
						if (UserRegistry.userIsAdmin(ctx.session.user)) {
							ctx.print("<br/>");
							printLiveUserMenu(ctx, theUser);
						}
						ctx.println("</td>");
					} else if (theirLast != null) {
						ctx.println("<td>");
						ctx.println("<b>seen: " + timeDiff(theirLast.getTime())
								+ " ago</b>");
						ctx.print("<br/><font size='-2'>");
						ctx.print(theirLast.toString());
						ctx.println("</font></td>");
					}

					ctx.println("  </tr>");
				}
				ctx.println("</table>");

				if (hideUserList) {
					ctx.println("</div>");
				}
				if (!justme) {
					ctx.println("<div style='font-size: 70%'>Number of users shown: "
							+ n + "</div><br>");
					
					if(n==0&&just!=null&&!just.isEmpty()) {
						UserRegistry.User u = reg.get(just);
						if(u==null) {
							ctx.println("<h3 class='ferrbox'>"+ctx.iconHtml("stop", "Not Found Error") + " User '"+just+"' does not exist.</h3>");
						} else {
							ctx.println("<h3 class='ferrbox'>"+ctx.iconHtml("stop", "Not Found Error") + " User '"+just+"' from organization " + u.org + " is not visible to you. Ask an administrator.</h3>");
						}
					}
					
					if (UserRegistry.userIsTC(ctx.session.user) && locked > 0) {
						showTogglePref(subCtx, PREF_SHOWLOCKED, "Show "
								+ locked + " locked users:");
					}
				}
				if (!justme
						&& UserRegistry.userCanModifyUsers(ctx.session.user)) {
					if ((n > 0)
							&& UserRegistry.userCanEmailUsers(ctx.session.user)) { // send
																					// a
																					// mass
																					// email
																					// to
																					// users
						if (ctx.field(LIST_MAILUSER).length() == 0) {
							ctx.println("<label><input type='checkbox' value='y' name='"
									+ LIST_MAILUSER
									+ "'>Check this box to compose a message to these "
									+ n
									+ " users (excluding LOCKED users).</label>");
						} else {
							ctx.println("<p><div class='pager'>");
							ctx.println("<h4>Mailing " + n + " users</h4>");
							if (didConfirmMail) {
								if (areSendingDisp) {
									int nm = vet
											.doDisputeNag(
													mailBody,
													UserRegistry
															.userIsAdmin(ctx.session.user) ? null
															: ctx.session.user.org);
									ctx.println("<b>dispute note sent: " + nm
											+ " emails sent.</b><br>");
								} else {
									ctx.println("<b>Mail sent.</b><br>");
								}
							} else { // dont' allow resend option
								if (sendWhat.length() > 0) {
									ctx.println("<label><input type='checkbox' value='y' name='"
											+ LIST_MAILUSER
											+ "_d'>Check this box to send a Dispute complaint with this email.</label><br>");
								} else {
									ctx.println("<i>On the next page you will be able to choose a Dispute Report.</i><br>");
								}
								ctx.println("<input type='hidden' name='"
										+ LIST_MAILUSER + "' value='y'>");
							}
							ctx.println("From: <b>" + cleanEmail + "</b><br>");
							if (sendWhat.length() > 0) {
								ctx.println("<div class='odashbox'>"
										+ TransliteratorUtilities.toHTML
												.transliterate(sendWhat)
												.replaceAll("\n", "<br>")
										+ "</div>");
								if (!didConfirmMail) {
									ctx.println("<input type='hidden' name='"
											+ LIST_MAILUSER_WHAT
											+ "' value='"
											+ sendWhat.replaceAll("&", "&amp;")
													.replaceAll("'", "&quot;")
											+ "'>");
									if (!ctx.field(LIST_MAILUSER_CONFIRM)
											.equals(cleanEmail)
											&& (ctx.field(LIST_MAILUSER_CONFIRM)
													.length() > 0)) {
										ctx.println("<strong>"
												+ ctx.iconHtml("stop",
														"email did not match")
												+ "That email didn't match. Try again.</strong><br>");
									}
									ctx.println("To confirm sending, type the email address <tt class='codebox'>"
											+ cleanEmail
											+ "</tt> in this box : <input name='"
											+ LIST_MAILUSER_CONFIRM + "'>");
								}
							} else {
								ctx.println("<textarea NAME='"
										+ LIST_MAILUSER_WHAT
										+ "' id='body' ROWS='15' COLS='85' style='width:100%'></textarea>");
							}
							ctx.println("</div>");
						}

					}
				}
				// #level $name $email $org
				rs.close();

				// more 'My Account' stuff
				if (justme) {
					ctx.println("<hr>");
					// Is the 'interest locales' list relevant?
					String mainLocs[] = UserRegistry
							.tokenizeLocale(ctx.session.user.locales);
					if (mainLocs.length == 0) {
						boolean intlocs_change = (ctx.field("intlocs_change")
								.length() > 0);

						ctx.println("<h4>Notify me about these locale groups:</h4>");

						if (intlocs_change) {
							if (ctx.field("intlocs_change").equals("t")) {
								String newIntLocs = ctx.field("intlocs");

								String msg = reg.setLocales(ctx,
										ctx.session.user.id,
										ctx.session.user.email, newIntLocs,
										true);

								if (msg != null) {
									ctx.println(msg + "<br>");
								}
								UserRegistry.User newMe = reg
										.getInfo(ctx.session.user.id);
								if (newMe != null) {
									ctx.session.user.intlocs = newMe.intlocs; // update
								}
							}

							ctx.println("<input type='hidden' name='intlocs_change' value='t'>");
							ctx.println("<label>Locales: <input name='intlocs' ");
							if (ctx.session.user.intlocs != null) {
								ctx.println("value='"
										+ ctx.session.user.intlocs.trim()
										+ "' ");
							}
							ctx.println("</input></label>");
							if (ctx.session.user.intlocs == null) {
								ctx.println("<br><i>List languages only, separated by spaces.  Example: <tt class='codebox'>en fr zh</tt>. leave blank for 'all locales'.</i>");
							}
							// ctx.println("<br>Note: changing interest locales is currently unimplemented. Check back later.<br>");
						}

						ctx.println("<ul><tt class='codebox'>"
								+ UserRegistry
										.prettyPrintLocale(ctx.session.user.intlocs)
								+ "</tt>");
						if (!intlocs_change) {
							ctx.print("<a href='" + ctx.url()
									+ ctx.urlConnector() + "do=listu&"
									+ LIST_JUST + "="
									+ changeAtTo40(ctx.session.user.email)
									+ "&intlocs_change=b' >[Change this]</a>");
						}
						ctx.println("</ul>");

					} // end intlocs
					ctx.println("<br>");
					// ctx.println("<input type='submit' disabled value='Reset Password'>");
					// /* just mean to show it as disabled */
				}
				if (justme || UserRegistry.userCanModifyUsers(ctx.session.user)) {
					ctx.println("<br>");
					ctx.println("<input type='submit' name='doBtn' value='Do Action'>");
					ctx.println("</form>");
				}
			}/* end synchronized(reg) */
		} catch(SQLException se) {
            SurveyLog.logger.log(java.util.logging.Level.WARNING,"Query for org " + org + " failed: " + DBUtils.unchainSqlException(se),se);
            ctx.println("<i>Failure: " + DBUtils.unchainSqlException(se) + "</i><br>");
        } finally {
    		DBUtils.close(conn);
        }
        if(just!=null) {
            ctx.println("<a href='"+ctx.url()+ctx.urlConnector()+"do=list'>\u22d6 Show all users</a><br>");
        }
        printFooter(ctx);
    }
	/**
	 * @param ctx
	 * @param userEmail
	 */
	private void printUserZoomLink(WebContext ctx, String userEmail) {
		printUserZoomLink(ctx, userEmail, "");
	}
	/**
	 * @param ctx
	 * @param userEmail
	 * @param text TODO
	 */
	private void printUserZoomLink(WebContext ctx, String userEmail, String text) {
		ctx.print("<a href='" + ctx.url() + ctx.urlConnector()
				+ "do=list&" + LIST_JUST + "="
				+ changeAtTo40(userEmail) + "' >"
				+ ctx.iconHtml("zoom", "More on this user..") + text
				+ "</a>");
	}
    
    private void doChangeUserOption(WebContext ctx, int newLevel, int theirLevel, boolean selected)
    {
        if(UserRegistry.userCanChangeLevel(ctx.session.user, theirLevel, newLevel)) {
            ctx.println("    <option " + /* (selected?" SELECTED ":"") + */ "value='" + LIST_ACTION_SETLEVEL + newLevel + "'>Make " +
                        UserRegistry.levelToStr(ctx,newLevel) + "</option>");
        }
    }

    /** 
     * Show a toggleable preference
     * @param ctx
     * @param pref which preference
     * @param what description of preference
     */
    public boolean showTogglePref(WebContext ctx, String pref, String what) {
        boolean val = ctx.prefBool(pref);
        WebContext nuCtx = (WebContext)ctx.clone();
        nuCtx.addQuery(pref, !val);
//        nuCtx.println("<div class='pager' style='float: right;'>");
        nuCtx.println("<a href='" + nuCtx.url() + "'>" + what + " is currently ");
        ctx.println(
            ((val)?"<span class='selected'>On</span>":"<span style='color: #ddd' class='notselected'>On</span>") + 
                "&nbsp;/&nbsp;" +
            ((!val)?"<span class='selected'>Off</span>":"<span style='color: #ddd' class='notselected'>Off</span>") );
        ctx.println("</a><br>");
//        nuCtx.println("</div>");
        return val;
    }
    
    String showListPref(WebContext ctx, String pref, String what, String[] list) {
        return showListPref(ctx,pref,what,list,false);
    }
    String showListPref(WebContext ctx, String pref, String what, String[] list, boolean doDef) {
        String val = ctx.pref(pref, doDef?"default":list[0]);
        ctx.println("<b>"+what+"</b>: ");
//        ctx.println("<select name='"+pref+"'>");
        if(doDef) {
            WebContext nuCtx = (WebContext)ctx.clone();
            nuCtx.addQuery(pref, "default");
            ctx.println("<a href='"+nuCtx.url()+"' class='"+(val.equals("default")?"selected":"notselected")+"'>"+"default"+"</a> ");
        }
        for(int n=0;n<list.length;n++) {
//            ctx.println("    <option " + (val.equals(list[n])?" SELECTED ":"") + "value='" + list[n] + "'>"+list[n] +"</option>");
            WebContext nuCtx = (WebContext)ctx.clone();
            nuCtx.addQuery(pref, list[n]);
            ctx.println("<a href='"+nuCtx.url()+"' class='"+(val.equals(list[n])?"selected":"notselected")+"'>"+list[n]+"</a> ");
        }
//    ctx.println("</select></label><br>");
        ctx.println("<br>");
        return val;
    }
    /*
     *             ctx.println("(settings type: " + ctx.settings().getClass().getName()+")<br/>");
            
            // If a pref (URL) is set, use that, otherwise if there is a setting use that, otherwise false.
            boolean dummySetting = ctx.prefBool("dummy",ctx.settings().get("dummy",false));
            // always set.
            ctx.settings().set("dummy",dummySetting);

            showTogglePref(ctx, "dummy", "A Dummy Setting");

     */
    String getListSetting(WebContext ctx, String pref, String[] list) {
    	return getListSetting(ctx,pref,list,false);
    }
    
    boolean getBoolSetting(WebContext ctx, String pref) {
    	return ctx.prefBool(pref,ctx.settings().get(pref,false));
    }
    
    String getListSetting(WebContext ctx, String pref, String[] list, boolean doDef) {
        String settingsSet = ctx.settings().get(pref, doDef?"default":list[0]);
    	String val = ctx.pref(pref, settingsSet);
    	return val;
    }
    static void writeMenu(WebContext jout, String title, String field, String current, String items[])  {
    	writeMenu(jout,title,field,current,items,null);
    }
    static void writeMenu(WebContext jout, String title, String field, String current, String items[], String rec)  {
    	String which = current;
		boolean any = false;
		for (int i = 0; !any && (i < items.length); i++) {
			if (items[i].equals(which))
				any = true;
		}

		String hash = "menu_"+field;
		
		jout.println("<label id='m_"+hash+"' class='"
				+ (!current.equals(items[0]) ? "menutop-active" : "menutop-other") + "' >");
		
//		if(!any) {
//			WebContext ssc = new WebContext(jout);
//			ssc.setQuery(field, items[0]);
//			//jout.println("<a href='"+ssc.url()+"' style='text-decoration: none;'>");
//		}
		jout.println(title);
//		if(!any) {
//			//jout.println("</a>");
//		}

		jout.println("<select class='"
				+ (any ? "menutop-active" : "menutop-other")
				+ "' onchange='window.location=this.value;'>"); //  document.getElementById(\"m_"+hash+"\").innerHTML+=\"<i>Changing to \" + this.text + \"...</i>\";
		if (!any) {
			jout.println("<option selected value=\"\">Change...</option>");
		}
		for (int i = 0; i < items.length; i++) {
			WebContext ssc = new WebContext(jout);
			ssc.setQuery(field, items[i]);
			String sty = "";
			if(rec!=null&&rec.equals(items[i])) {
				//jout.print("<optgroup label=\"Recommended\">");
				sty="font-weight: bold;";
			}
			
			jout.print("<option style='"+sty+"' ");
			if (items[i].equals(which)) {
				jout.print(" selected ");
			} else {
				jout.print("value=\"" + ssc.url() + "\" ");
			}
			jout.print(">" + items[i]);
            if(rec!=null&&rec.equals(items[i])) {
                jout.print("*");
            }
			jout.println("</option>");

//			if(rec!=null&&rec.equals(items[i])) {
//				jout.print("</optgroup>");
//			}
		
		}
		jout.println("</select>");
		jout.println("</label>");
	}
    
    String showListSetting(WebContext ctx, String pref, String what, String[] list) {
    	return showListSetting(ctx,pref,what,list,false);
    }

    String showListSetting(WebContext ctx, String pref, String what, String[] list, boolean doDef) {
    	return showListSetting(ctx,pref,what,list,doDef,null);
    }
    String showListSetting(WebContext ctx, String pref, String what, String[] list, String rec) {
    	return showListSetting(ctx,pref,what,list,false,rec);
    }
    String showListSetting(WebContext ctx, String pref, String what, String[] list, boolean doDef, String rec) {
    	String val = getListSetting(ctx,pref,list,doDef);
        ctx.settings().set(pref, val);
        
        boolean no_js = ctx.prefBool(SurveyMain.PREF_NOJAVASCRIPT);

        if(no_js) {
	        ctx.println("<b>"+what+"</b>: ");
	        if(doDef) {
	            WebContext nuCtx = (WebContext)ctx.clone();
	            nuCtx.addQuery(pref, "default");
	            ctx.println("<a href='"+nuCtx.url()+"' class='"+(val.equals("default")?"selected":"notselected")+"'>"+"default"+"</a> ");
	        }
	        for(int n=0;n<list.length;n++) {
	            WebContext nuCtx = (WebContext)ctx.clone();
	            nuCtx.addQuery(pref, list[n]);
	            if(rec!=null&&rec.equals(list[n])) {
	            	ctx.print("<b>");
	            }
	            ctx.println("<a href='"+nuCtx.url()+"' class='"+(val.equals(list[n])?"selected":"notselected")+"'>"+list[n]+"</a> ");
	            if(rec!=null&&rec.equals(list[n])) {
	            	ctx.print("*</b>");
	            }
	        }
	        ctx.println("<br>");
        } else {
        	writeMenu(ctx,what,pref,val,list, rec);
        }
        
        
        return val;
    }

    void doDisputed(WebContext ctx){
        printHeader(ctx, "Disputed Items Page");
        printUserTableWithHelp(ctx, "/DisputedItems");
        
        ctx.println("<a href='"+ctx.url()+"'>Return to SurveyTool</a><hr>");

        vet.doOrgDisputePage(ctx);

        ctx.addQuery(QUERY_DO,"disputed");

        ctx.println("<h2>Disputed Items</h2>");

        vet.doDisputePage(ctx);
        
        printFooter(ctx);
    } 
	
    void doOptions(WebContext ctx) {
        WebContext subCtx = new WebContext(ctx);
        subCtx.removeQuery(QUERY_DO);
        printHeader(ctx, "My Options");
        printUserTableWithHelp(ctx, "/MyOptions");
        
        ctx.println("<a href='"+ctx.url()+"'>Return to SurveyTool</a><hr>");
        printRecentLocales(subCtx, ctx);
        ctx.addQuery(QUERY_DO,"options");
        ctx.println("<h2>My Options</h2>");
  
  
        ctx.println("<h4>Coverage</h4>");
        ctx.print("<blockquote>");
        ctx.println("<p class='hang'>For more information on coverage, see "+
            "<a href='http://www.unicode.org/cldr/data/docs/web/survey_tool.html#Coverage'>Coverage Help</a></p>");
        //String lev = showListPref(ctx, PREF_COVLEV, "Coverage Level", PREF_COVLEV_LIST);
        String lev = ctx.showCoverageSetting();
        
      /*if(false) { // currently not doing effective coverages
        if(lev.equals("default")) {
            ctx.print("&nbsp;");
            ctx.print("&nbsp;");
            showListPref(ctx,PREF_COVTYP, "Coverage Type", ctx.getLocaleTypes(), true);
        } else {
            ctx.print("&nbsp;");
            ctx.print("&nbsp;<span class='deactivated'>Coverage Type: <b>n/a</b></span><br>");
        }
            ctx.println("<br>(Current effective coverage level: <tt class='codebox'>" + ctx.defaultPtype()+"</tt>)<p>");
      }*/
        	ctx.println("<br/>Current coverage level: <tt class='codebox'>" + lev +"</tt>.<br>");
        	if(!ctx.settings().persistent()) {
        	    ctx.println("<i>Note: Your coverage level won't be saved, you are not logged in.</i><br/>");
        	} else {
        	    ctx.println("(Your coverage level will be saved for the next time you log in.)<br/>");
        	}
            ctx.println("</blockquote>");
            
   
        ctx.print("<hr>");

		if(UserRegistry.userIsTC(ctx.session.user)) {
		    showTogglePref(ctx, PREF_DELETEZOOMOUT, "Show delete controls when not zoomed in:");
		    showTogglePref(ctx, PREF_SHOWUNVOTE, "Show controls for removing votes:");
		}
		
        ctx.println("<h4>Advanced Options</h4>");
        ctx.print("<blockquote>");
        boolean adv = showToggleSetting(ctx, PREF_ADV, "Show Advanced Options");
        ctx.println("</blockquote>");

        
        if(adv == true) {
            ctx.println("<div class='ferrbox'><i>Do not enable these items unless instructed.</i><br>");
            showTogglePref(ctx, PREF_NOPOPUPS, "Reduce popup windows");
            showTogglePref(ctx, PREF_XPID, "show XPATH ids");
            showTogglePref(ctx, PREF_GROTTY, "show obtuse items");
            showTogglePref(ctx, PREF_XPATHS, "Show full XPaths");
        	showToggleSetting(ctx, PREF_SHCOVERAGE, "Show Coverage Levels");
            showTogglePref(ctx, PREF_NOSHOWDELETE, "Suppress controls for deleting unused items in zoomed-in view:");
            showTogglePref(ctx, PREF_NOJAVASCRIPT, "Reduce the use of JavaScript (unimplemented)");
            
            ctx.println("</div>");
        }

//        
//        // Dummy, pointless boolean toggle.
//        if(isUnofficial) {
//            ctx.println("(settings type: " + ctx.settings().getClass().getName()+")<br/>");
//            showToggleSetting(ctx, "dummy","A Dummy Setting");
//        }
                
        printFooter(ctx);
    }

    public boolean showToggleSetting(WebContext ctx, String field, String title) {
        // If a pref (URL) is set, use that, otherwise if there is a setting use that, otherwise false.
        boolean someSetting = getBoolSetting(ctx,field);
        // always set.
        ctx.settings().set(field,someSetting);

        showTogglePref(ctx, field, title);
        
        return someSetting;
	}
	/**
     * Print the opening form for a 'shopping cart' (one or more showPathList operations)
     * @see showPathList
     * @see printPathListClose
     */
    public void printPathListOpen(WebContext ctx) {
        	ctx.println("<form name='"+STFORM+"' method=POST action='" + ctx.base() + "'>");
    }
    
    /**
     * Print the closing form for a 'shopping cart' (one or more showPathList operations)
     * @see showPathList
     * @see printPathListOpen
     */
    public void printPathListClose(WebContext ctx) {
        	ctx.println("</form>");
    }
    
    public void doSession(WebContext ctx) throws IOException
    {
        // which 
        String which = ctx.field(QUERY_SECTION);
        
        setLocale(ctx);
        
        String sessionMessage = setSession(ctx);
        
        if(ctx.session == null) {
        	return;
        }
        
        if(lockOut != null) {
            if(ctx.field("unlock").equals(lockOut)) {
                ctx.session.put("unlock",lockOut);
            } else {
                String unlock = (String)ctx.session.get("unlock");
                if((unlock==null) || (!unlock.equals(lockOut))) {
                    printHeader(ctx, "Locked for Maintenance");
                    ctx.print("<hr><div class='ferrbox'>Sorry, the Survey Tool has been locked for maintenance work. Please try back later.</div>");
                    printFooter(ctx);
                    return;
                }
            }
        }
        
        // setup thread name
        if(ctx.session.user != null) {
        	Thread.currentThread().setName(Thread.currentThread().getName()+" "+ctx.session.user.id+":"+ctx.session.user.toString());        	

            if(ctx.hasField(QUERY_SAVE_COOKIE)) {
                int TWELVE_WEEKS=3600*24*7*12;
                ctx.addCookie(QUERY_EMAIL, ctx.session.user.email, TWELVE_WEEKS);
                ctx.addCookie(QUERY_PASSWORD, ctx.session.user.password, TWELVE_WEEKS);
            }
        }
        if(ctx.hasField(SurveyForum.F_FORUM) || ctx.hasField(SurveyForum.F_XPATH)) {
            fora.doForum(ctx, sessionMessage);
            return;
        }
        
        // Redirect references to language locale
        if(ctx.field("x").equals("references")) {
            if(ctx.getLocale() != null) {
                String langLocale = ctx.getLocale().getLanguage();
                if(!langLocale.equals(ctx.getLocale().toString())) {
                    ctx.redirect(ctx.base()+"?_="+langLocale+"&x=references");
                    return;
                }
            } else {
                ctx.redirect(ctx.base());
                return;
            }
        }
        
        // TODO: untangle this
        // admin things
        if((ctx.field(QUERY_DO).length()>0)) {
            String doWhat = ctx.field(QUERY_DO);

            // could be user or non-user items
            if(doWhat.equals("options")) {
                doOptions(ctx);
                return;
            } else if(doWhat.equals("disputed")) {
                doDisputed(ctx);
                return;
            } else if(doWhat.equals("logout")) {
                    ctx.session.remove(); // zap!
                    HttpSession httpSession = ctx.request.getSession(false);
                    if(httpSession != null) {
                        httpSession.removeAttribute(SURVEYTOOL_COOKIE_SESSION);
                    }

                    try {
                        ctx.addCookie(QUERY_PASSWORD, "", 0);
                        ctx.addCookie(QUERY_EMAIL, "", 0);
                        ctx.response.sendRedirect(ctx.jspLink("?logout=1"));
                        ctx.out.close();
                        ctx.close();
                    } catch(IOException ioe) {
                        throw new RuntimeException(ioe.toString() + " while redirecting to logout");
                    }
                    return;
            }
            
            // these items are only for users.
            if(ctx.session.user != null) {
                if((doWhat.equals("list")||doWhat.equals("listu")) && (UserRegistry.userCanDoList(ctx.session.user))  ) {
                    doList(ctx);
                    return;
                } else if(doWhat.equals("coverage")  && (UserRegistry.userCanDoList(ctx.session.user))  ) {
                    doCoverage(ctx);
                    return;
                } else if(doWhat.equals("new") && (UserRegistry.userCanCreateUsers(ctx.session.user)) ) {
                    doNew(ctx);
                    return;
                }
            }
            // Option wasn't found
            sessionMessage = ("<i id='sessionMessage'>Could not do the action '"+doWhat+"'. You may need to be logged in first.</i>");
        }
        
        String title = " " + which;
        if(ctx.hasField(QUERY_EXAMPLE))  {
            title = title + " Example"; 
        } else if(which==null || which.isEmpty()){
            if(ctx.getLocale() == null) {
                title = "Locales";
            } else {
                title = " general";
            }
        } else if(which.equals(R_STEPS)) {
            title = " Basic Locale Information";
        }
        printHeader(ctx, title);
        if(sessionMessage != null) {
            ctx.println(sessionMessage);
        }
        
        WebContext baseContext = (WebContext)ctx.clone();
        

        // print 'shopping cart'
        if(!shortHeader(ctx))  {
            
            if((which.length()==0) && (ctx.getLocale()!=null)) {
                which = xMAIN;
            }
         //   if(false&&(which.length()>0)) {
         // printUserTableWithHelp(ctx, "/"+which.replaceAll(" ","_")); // Page Help
         //   } else {
                printUserTable(ctx);
         //   }
            printRecentLocales(baseContext, ctx);
        }
        
        if((ctx.getLocale() != null) && 
            (!shortHeader(ctx)) ) { // don't show these warnings for example/easy steps pages.
            CLDRLocale dcParent = CLDRLocale.getInstance(supplemental.defaultContentToParent(ctx.getLocale().toString()));
            String dcChild = supplemental.defaultContentToChild(ctx.getLocale().toString());
            if(dcParent != null) {
                ctx.println("<b><a href=\"" + ctx.url() + "\">" + "Locales" + "</a></b><br/>");
                ctx.println("<h1 title='"+ctx.getLocale().getBaseName()+"'>"+ctx.getLocale().getDisplayName(ctx.displayLocale)+"</h1>");
                ctx.println("<div class='ferrbox'>This locale is the default content for <b>"+
                    getLocaleLink(ctx,dcParent,null)+
                    "</b>; thus editing and viewing is disabled. Please view and/or propose changes in <b>"+
                    getLocaleLink(ctx,dcParent,null)+
                    "</b> instead.  ");
                ctx.printHelpLink("/DefaultContent","Help with Default Content");
                ctx.print("</div>");
                
                //printLocaleTreeMenu(ctx, which);
                printFooter(ctx);
                return; // Disable viewing of default content
                
            } else if (dcChild != null) {
                String dcChildDisplay = new ULocale(dcChild).getDisplayName(ctx.displayLocale);
                ctx.println("<div class='fnotebox'>This locale supplies the default content for <b>"+
                    dcChildDisplay+
                    "</b>. Please make sure that all the changes that you make here are appropriate for <b>"+
                    dcChildDisplay+
                    "</b>. If you add any changes that are inappropriate for other sublocales, be sure to override their values. ");
                ctx.printHelpLink("/DefaultContent","Help with Default Content");
                ctx.print("</div>");
            }
            CLDRLocale aliasTarget = isLocaleAliased(ctx.getLocale());
            if(aliasTarget != null) {
                // the alias might be a default content locale. Save some clicks here. 
                dcParent = CLDRLocale.getInstance(supplemental.defaultContentToParent(aliasTarget.toString()));
                if(dcParent == null) {
                    dcParent = aliasTarget;
                }
                ctx.println("<div class='ferrbox'>This locale is aliased to <b>"+
                    getLocaleLink(ctx,aliasTarget,null)+
                    "</b>. You cannot modify it. Please make all changes in <b>"+
                    getLocaleLink(ctx,dcParent,null)+
                    "</b>.<br>");
                ctx.printHelpLink("/AliasedLocale","Help with Aliased Locale");
                ctx.print("</div>");
                

                ctx.println("<div class='ferrbox'><h1>"+ctx.iconHtml("stop",null)+"We apologise for the inconvenience, but there is currently an error with how these aliased locales are resolved.  Kindly ignore this locale for the time being. You must make all changes in <b>"+
                    getLocaleLink(ctx,dcParent,null)+
                    "</b>.</h1>");
                ctx.print("</div>");
                
                
            }
        }
        
        doLocale(ctx, baseContext, which);
    }
    
    private void printRecentLocales(WebContext baseContext, WebContext ctx) {
        Hashtable lh = ctx.session.getLocales();
        Enumeration e = lh.keys();
        if(e.hasMoreElements()) {
            boolean shownHeader = false;
            for(;e.hasMoreElements();) {
                String k = e.nextElement().toString();
                if((ctx.getLocale()!=null)&&(ctx.getLocale().toString().equals(k))) {
                    continue;
                }
                if(!shownHeader) {
                    ctx.println("<p align='right'><B>Recent locales: </B> ");
                    shownHeader = true;
                }
                boolean canModify = UserRegistry.userCanModifyLocale(ctx.session.user,CLDRLocale.getInstance(k));
                ctx.print("<a href=\"" + baseContext.url() + ctx.urlConnector() + QUERY_LOCALE+"=" + k + "\">" + 
                            new ULocale(k).getDisplayName(ctx.displayLocale));
                if(canModify) {
                    ctx.print(modifyThing(ctx));
                }
                ctx.println("</a> ");
            }
            if(shownHeader) {
                ctx.println("</p>");
            }
        }
    }
    private static boolean shortHeader(WebContext ctx) {
        // TODO Auto-generated method stub
        return ctx.hasField(QUERY_EXAMPLE) || R_STEPS.equals(ctx.field(SurveyMain.QUERY_SECTION));
    }

    LocaleTree localeTree = null;
//    protected Map<String,CLDRLocale> getLocaleListMap()
//    {
//        return getLocaleTree().getMap();
//    }

    public CLDRLocale getLocaleCode(String localeName) {
        return getLocaleTree().getLocaleCode(localeName);
    }

    protected synchronized LocaleTree getLocaleTree() {
        if(localeTree == null) {
            LocaleTree newLocaleTree = new LocaleTree(BASELINE_LOCALE);
            File inFiles[] = getInFiles();
            if(inFiles == null) {
                busted("Can't load CLDR data files from " + fileBase);
                throw new RuntimeException("Can't load CLDR data files from " + fileBase);
            }
            int nrInFiles = inFiles.length;
            
            for(int i=0;i<nrInFiles;i++) {
                String localeName = inFiles[i].getName();
                int dot = localeName.indexOf('.');
                if(dot !=  -1) {
                    localeName = localeName.substring(0,dot);
                    CLDRLocale loc = CLDRLocale.getInstance(localeName);
                    
                    // but, is it just an alias?
                    CLDRLocale aliasTo = isLocaleAliased(loc);
                    if(aliasTo == null) {
                    	newLocaleTree.add(CLDRLocale.getInstance(localeName));
                    }
                }
            }
            localeTree = newLocaleTree;
        }
        return localeTree;
    }
    
    public static String getLocaleDisplayName(CLDRLocale locale) {
        return locale.getDisplayName(BASELINE_LOCALE);
    }

    void printLocaleStatus(WebContext ctx, CLDRLocale localeName, String str, String explanation) {
        ctx.print(getLocaleStatus(localeName,str,explanation));
    }
    
    String getLocaleStatus(CLDRLocale localeName, String str, String explanation) {
        String rv = "";
        int s = vet.status(localeName);
        if(s==-1) {
            if(explanation.length()>0) {
                rv = rv + ("<span title='"+explanation+"'>");
            }
            rv = rv + (str);
            if(explanation.length()>0) {
                rv = rv + ("</span>");
            }
            return rv;
        }
        if((s&Vetting.RES_DISPUTED)>0) {
            rv = rv + ("<span style='margin: 1px; padding: 1px;' class='disputed'>");
            if(explanation.length() >0) {
                explanation = explanation + ", ";
            }
            explanation = explanation + "disputed";
        } else {
			if ((s&(Vetting.RES_INSUFFICIENT|Vetting.RES_NO_VOTES))>0) {
				rv = rv + ("<span style='margin: 1px; padding: 1px;' class='insufficient'>");
				if(explanation.length() >0) {
					explanation = explanation + ", ";
				}
				explanation = explanation + "insufficient votes";
			}
		}
        if(explanation.length()>0) {
            rv = rv + ("<span title='"+explanation+"'>");
        }
        rv = rv + (str);
        if(explanation.length()>0) {
            rv = rv + ("</span>");
        }
        if((s&Vetting.RES_DISPUTED)>0) {
            rv = rv + ("</span>");
        } else if ((s&(Vetting.RES_INSUFFICIENT|Vetting.RES_NO_VOTES))>0) {
            rv = rv + ("</span>");
        }
        return rv;
    }
    
    String getLocaleLink(WebContext ctx, CLDRLocale locale, String n) {
        if(n == null) {
            n = locale.getDisplayName(ctx.displayLocale) ;
        }
        String connector = ctx.urlConnector();
//        boolean hasDraft = draftSet.contains(localeName);
//        ctx.print(hasDraft?"<b>":"") ;
        String title = locale.toString();
        String classstr = "";
        
        String rv = 
            ("<a " +classstr
           +" title='" + title + "' href=\"" + ctx.url() 
                  + connector + QUERY_LOCALE+"=" + locale.getBaseName() + "\">");
        rv = rv + getLocaleStatus(locale, n, locale.toString());
        boolean canModify = UserRegistry.userCanModifyLocale(ctx.session.user,locale);
        if(canModify) {
            rv = rv + (modifyThing(ctx));
            int odisp = 0;
            if((this.phase()==Phase.VETTING || this.phase() == Phase.SUBMIT || isPhaseVettingClosed()) && ((odisp=vet.getOrgDisputeCount(ctx.session.user.voterOrg(),locale))>0)) {
                rv = rv + ctx.iconHtml("disp","("+odisp+" org disputes)");
            }
        }
        rv = rv + ("</a>");
//        ctx.print(hasDraft?"</b>":"") ;

        return rv;
    }
    
    void printLocaleLink(WebContext ctx, CLDRLocale localeName, String n) {
        ctx.print(getLocaleLink(ctx,localeName,n));
    }


    /*
     * show a list of locales that fall into this interest group.
     */
    void printListFromInterestGroup(WebContext ctx, String intgroup) {
        LocaleTree lm = getLocaleTree();
        if(lm == null) {
            throw new RuntimeException("Can't load CLDR data files");
        }
        ctx.println("<table summary='Locale List' border=1 class='list'>");
        int n=0;
        for(String ln : lm.getTopLocales()) {
            CLDRLocale aLocale = lm.getLocaleCode(ln);
            
            if(aLocale==null) throw new InternalError("printListFromInterestGroup: can't find intgroup for locale "+ln);
            ULocale uLocale = aLocale.toULocale();
            if(!intgroup.equals(aLocale.getLanguage())) {
                continue;
            }
            
            n++;
            ctx.print("<tr class='row" + (n%2) + "'>");
            ctx.print(" <td valign='top'>");
            printLocaleLink(ctx, (aLocale), ln.toString());
            ctx.println(" </td>");
            
            Map<String, CLDRLocale> sm = lm.getSubLocales(aLocale);
            
            ctx.println("<td valign='top'>");
            int j = 0;
            for(Iterator si = sm.keySet().iterator();si.hasNext();) {
                if(j>0) { 
                    ctx.println(", ");
                }
                String sn = (String)si.next();
                CLDRLocale subLocale = sm.get(sn);
//                if(subLocale.length()>0) {
                    printLocaleLink(ctx, (subLocale), sn);
//                }
                j++;
            }
            ctx.println("</td");
            ctx.println("</tr>");
        }
        ctx.println("</table> ");
    }
        
    void doLocaleList(WebContext ctx, WebContext baseContext) {
        boolean showCodes = ctx.prefBool(PREF_SHOWCODES);
        
        {
            WebContext nuCtx = (WebContext)ctx.clone();
            nuCtx.addQuery(PREF_SHOWCODES, !showCodes);
            nuCtx.println("<div class='pager' style='float: right;'>");
            nuCtx.println("<a href='" + nuCtx.url() + "'>" + ((!showCodes)?"Show":"Hide") + " locale codes</a>");
            nuCtx.println("</div>");
        }
        ctx.println("<h1>Locales</h1>");

        ctx.print(fora.mainFeedIcon(ctx)+"<br>");
				
        ctx.println(SLOW_PAGE_NOTICE);
        LocaleTree lm = getLocaleTree();
//        if(lm == null) {
//            busted("Can't load CLDR data files from " + fileBase);
//            throw new RuntimeException("Can't load CLDR data files from " + fileBase);
//        }

        ctx.println("<table summary='Locale List' border=1 class='list'>");
        int n=0;
        for(String ln:lm.getTopLocales()) {
            n++;
            CLDRLocale aLocale = lm.getLocaleCode(ln);
            ctx.print("<tr class='row" + (n%2) + "'>");
            ctx.print(" <td valign='top'>");
            printLocaleLink(baseContext, (aLocale), ln);
            if(showCodes) {
                ctx.println("<br><tt>" + aLocale + "</tt>");
            }
            ctx.println(" </td>");
            
            Map<String,CLDRLocale> sm = lm.getSubLocales(aLocale);
            
            ctx.println("<td valign='top'>");
            int j = 0;
            for(Iterator si = sm.keySet().iterator();si.hasNext();) {
                if(j>0) { 
                    ctx.println(", ");
                }
                String sn = (String)si.next();
                CLDRLocale subLocale = sm.get(sn);
//                if(subLocale.length()>0) {
                    printLocaleLink(baseContext, (subLocale), sn);
                    if(showCodes) {
                        ctx.println("&nbsp;-&nbsp;<tt>" + subLocale + "</tt>");
                    }
//                }
                j++;
            }
            ctx.println("</td>");
            ctx.println("</tr>");
        }
        ctx.println("</table> ");
//        ctx.println("(Locales containing draft items are shown in <b><a class='draftl' href='#'>bold</a></b>)<br/>");
// TODO: reenable this        
    }
    
    void doLocale(WebContext ctx, WebContext baseContext, String which) {
        String locale = null;
        if(ctx.getLocale() != null) {
            locale = ctx.getLocale().toString();
        }
        if(!shortHeader(ctx))  {
        	printPathListOpen(ctx);
        }
        if((locale==null)||(locale.length()<=0)) {
            doLocaleList(ctx, baseContext);            
            ctx.println("<br/>");
        } else {
            showLocale(ctx, which);
        }
        if(!shortHeader(ctx))  {
            printPathListClose(ctx);
        }
        printFooter(ctx);
    }
    
    /**
     * Print out a menu item
     * @param ctx the context
     * @param which the ID of "this" item
     * @param menu the ID of the current item
     */
    public void printMenu(WebContext ctx, String which, String menu) {
        printMenu(ctx,which,menu,menu, QUERY_SECTION);
    }
    /**
     * Print out a menu item
     * @param ctx the context
     * @param which the ID of "this" item
     * @param menu the ID of the current item
     * @param anchor the #anchor to link to
     */
    protected void printMenuWithAnchor(WebContext ctx, String which, String menu, String anchor) {
        printMenu(ctx,which,menu,menu, QUERY_SECTION, anchor);
    }
    /**
     * Print out a menu item
     * @param ctx the context
     * @param which the ID of "this" item
     * @param menu the ID of the current item
     * @param title the Title of this menu
     */
    public void printMenu(WebContext ctx, String which, String menu, String title) {
        printMenu(ctx,which,menu,title,QUERY_SECTION);
    }
    /**
     * Print out a menu item
     * @param ctx the context
     * @param which the ID of "this" item
     * @param menu the ID of the current item
     * @param title the Title of this menu
     * @param key the URL field to use (such as 'x')
     */
    public void printMenu(WebContext ctx, String which, String menu, String title, String key) {
		printMenu(ctx,which,menu,title,key,null);
	}
    /**
     * Print out a menu item
     * @param ctx the context
     * @param which the ID of "this" item
     * @param menu the ID of the current item
     * @param title the Title of this menu
     * @param key the URL field to use (such as 'x')
     * @param anchor the #anchor to link to
     */
    public void printMenu(WebContext ctx, String which, String menu, String title, String key, String anchor) {
        ctx.print(getMenu(ctx,which, menu, title, key, anchor));
    }
    
    /**
     * Print out a menu as a form
     * @param ctx
     * @param which
     * @param menu
     * @param title
     * @param key
     */
    private void printMenuButton(WebContext ctx, String which,
            String menu, String title, String key, String buttonName) {
        ctx.print("<form action='"+ctx.url()+"#"+key+"="+menu+"' method='POST'>");
        ctx.printUrlAsHiddenFields();
        ctx.println("\n<input type='hidden' name='"+key+"' value='"+menu+"' />");
        ctx.println("<label class='"+(menu.equals(which)?"selected":"notselected")+"'>"+buttonName);
        ctx.println("<input type='submit' value='"+title+"' />");
        ctx.println("</label>");
        ctx.println("</form>");
    }

    
    public String getMenu(WebContext ctx, String which, String menu, String title) {
        return getMenu(ctx,which,menu,title,QUERY_SECTION);
    }

    
    public String getMenu(WebContext ctx, String which, String menu, String title, String key) {
        return getMenu(ctx,which,menu,title,key,null);
    }

    
    public static String getMenu(WebContext ctx, String which, String menu, String title, String key, String anchor) {
        StringBuffer buf = new StringBuffer();
        if(menu.equals(which)) {
            buf.append("<b class='selected'>");
        } else {
            buf.append("<a class='notselected' href=\"" + ctx.url() + ctx.urlConnector() + key+"=" + menu +
					((anchor!=null)?("#"+anchor):"") +
                      "\">");
        }
        if(menu.endsWith("/")) {
            buf.append(title + "<font size=-1>(other)</font>");
        } else {
            buf.append(title);
        }
        if(menu.equals(which)) {
            buf.append("</b>");
        } else {
            buf.append("</a>");
        }
        return buf.toString();
    }

    void mailUser(WebContext ctx, String theirEmail, String subject, String message) {
        String from = survprops.getProperty("CLDR_FROM","nobody@example.com");
        mailUser(ctx, getRequesterEmail(ctx), getRequesterName(ctx), theirEmail, subject, message);
    }
    
    void mailUser(WebContext ctx, String mailFromAddress, String mailFromName, String theirEmail, String subject, String message) {
        String requester = getRequester(ctx);
        String from = survprops.getProperty("CLDR_FROM","nobody@example.com");
        String smtp = survprops.getProperty("CLDR_SMTP",null);
        
        if(smtp == null) {
            ctx.println(ctx.iconHtml("okay","mail sent")+"<i>Not sending mail- SMTP disabled.</i><br/>");
            ctx.println("<hr/><pre>" + message + "</pre><hr/>");
            smtp = "NONE";
        } else {
            MailSender.sendMail(smtp, mailFromAddress, mailFromName,  from, theirEmail, subject,
                                message);
            ctx.println("<br>"+ctx.iconHtml("okay","mail sent")+"Mail sent to " + theirEmail + " from " + from + " via " + smtp + "<br/>\n");
        }
        SurveyLog.logger.info( "Mail sent to " + theirEmail + "  from " + from + " via " + smtp + " - "+subject);
        /* some debugging. */
    }
    
    String getRequesterEmail(WebContext ctx) {
        String cleanEmail = ctx.session.user.email;
        if(cleanEmail.equals("admin@")) {
            cleanEmail = "surveytool@unicode.org";
        }
        return cleanEmail;
    }
    
    String getRequesterName(WebContext ctx) {
        return ctx.session.user.name;
    }
    
    String getRequester(WebContext ctx) {
        String requester = getRequesterName(ctx) + " <" + getRequesterEmail(ctx) + ">";
        return requester;
    }
    
    void notifyUser(WebContext ctx, String theirEmail, String pass) {
        String body = getRequester(ctx) +  " is notifying you of the CLDR vetting account for you.\n" +
        "To access it, visit: \n" +
        "   http://" + ctx.serverHostport() + ctx.base() + "?"+QUERY_PASSWORD+"=" + pass + "&"+QUERY_EMAIL+"=" + theirEmail + "\n" +
        //                                                                          // DO NOT ESCAPE THIS AMPERSAND.
        "\n" +
        "Or you can visit\n   http://" + ctx.serverHostport() + ctx.base() + "\n    username: " + theirEmail + "\n    password: " + pass + "\n" +
        "\n" +
        " Please keep this link to yourself. Thanks.\n" +
        " Follow the 'Instructions' link on the main page for more help.\n" +
        " \n";
        String subject = "CLDR Registration for " + theirEmail;
        mailUser(ctx, theirEmail,subject,body);
    }
    
    /**
     * Convert from the parent to a child type.  i.e. 'languages' -> 'language'
     */
    public static final String typeToSubtype(String type)
    {
        String subtype = type;
        if(type.equals(LDMLConstants.LANGUAGES)) {
            subtype = LDMLConstants.LANGUAGE;
        } else if(type.equals(LDMLConstants.SCRIPTS)) {
            subtype = LDMLConstants.SCRIPT;
        } else if(type.equals(PathUtilities.MEASNAMES)) {
            subtype = MEASNAME;
        } else if(type.equals(PathUtilities.CODEPATTERNS)) {
            subtype = CODEPATTERN;
        } else if(type.equals(LDMLConstants.TERRITORIES)) {
            subtype = LDMLConstants.TERRITORY;
        } else if(type.equals(LDMLConstants.VARIANTS)) {
            subtype = LDMLConstants.VARIANT;
        } else if(type.equals(LDMLConstants.KEYS)) {
            subtype = LDMLConstants.KEY;
        } else if(type.equals(LDMLConstants.TYPES)) {
            subtype = LDMLConstants.TYPE;
        } /* else if(subtype.endsWith("s")) {
            subtype = subtype.substring(0,subtype.length()-1);
        }
        */
        return subtype;
    }

    public static final String CHECKCLDR = "CheckCLDR_";  // key for CheckCLDR objects by locale
    public static final String CHECKCLDR_RES = "CheckCLDR_RES_";  // key for CheckCLDR objects by locale
    
    
    void printLocaleTreeMenu(WebContext ctx, String which) {
        int n = ctx.docLocale.length;
        int i,j;
        WebContext subCtx = (WebContext)ctx.clone();
        subCtx.addQuery(QUERY_LOCALE,ctx.getLocale().toString());

        ctx.println("<table summary='locale info' width='95%' border=0><tr><td >"); // width='25%'
        ctx.println("<b><a href=\"" + ctx.url() + "\">" + "Locales" + "</a></b><br/>");
        for(i=(n-1);i>0;i--) {
            for(j=0;j<(n-i);j++) {
                ctx.print("&nbsp;&nbsp;");
            }
            boolean canModify = UserRegistry.userCanModifyLocale(ctx.session.user,ctx.docLocale[i]);
            ctx.print("\u2517&nbsp;<a title='"+ctx.docLocale[i]+"' class='notselected' href=\"" + ctx.url() + ctx.urlConnector() +QUERY_LOCALE+"=" + ctx.docLocale[i] + 
                "\">");
            printLocaleStatus(ctx, ctx.docLocale[i], ctx.docLocale[i].toULocale().getDisplayName(ctx.displayLocale), "");
            if(canModify) {
                ctx.print(modifyThing(ctx));
            }
            ctx.print("</a> ");
            ctx.print("<br/>");
        }
        for(j=0;j<n;j++) {
            ctx.print("&nbsp;&nbsp;");
        }
        boolean canModifyL = UserRegistry.userCanModifyLocale(ctx.session.user,ctx.getLocale());
        ctx.print("\u2517&nbsp;");
        ctx.print("<span title='"+ctx.getLocale()+"' style='font-size: 120%'>");
        printMenu(subCtx, which, xMAIN, 
            getLocaleStatus(ctx.getLocale(), ctx.getLocale().getDisplayName(ctx.displayLocale)+(canModifyL?modifyThing(ctx):""), "") );
        ctx.print("</span>");
        ctx.println("<br/>");
        ctx.println("</td>");
        
        
        ctx.println("<td style='padding-left: 1em;'>");
        
        boolean canModify = UserRegistry.userCanModifyLocale(subCtx.session.user, subCtx.getLocale());
        subCtx.put("which", which);
        subCtx.put(WebContext.CAN_MODIFY, canModify);
        subCtx.includeFragment("menu_top.jsp"); // ' code lists .. ' etc

        // not in the jsp- advanced idems.
        if(ctx.prefBool(PREF_ADV)) {
            subCtx.println("<p class='hang'>Advanced Items: ");
//                printMenu(subCtx, which, TEST_MENU_ITEM);
            printMenu(subCtx, which, RAW_MENU_ITEM);
            subCtx.println("</p>");
        }
        

        subCtx.println("</td></tr></table>");
    }
    
    /**
     * show the actual locale data..
     * @param ctx context
     * @param which value of 'x' parameter.
     */
    public void showLocale(WebContext ctx, String which)
    {
    	if(HAVE_REMOVE&&which.equals(xREMOVE)) {
    		ctx.println("<b><a href=\"" + ctx.url() + "\">" + "List of Locales" + "</a></b><br/>");
    		ctx.session.getLocales().remove(ctx.field(QUERY_LOCALE));
    		ctx.println("<h2>Your session for " + ctx.field(QUERY_LOCALE) + " has been removed.</h2>");
    		doMain(ctx);
    		return;
    	}
    	UserLocaleStuff uf = null;
    	synchronized (ctx.session) {
    		uf = ctx.getUserFile();

    		CLDRFile cf = uf.cldrfile;
    		if(cf == null) {
    			throw new InternalError("CLDRFile is null!");
    		}
    		XMLSource ourSrc = uf.dbSource; // TODO: remove. debuggin'
    		if(ourSrc == null) {
    			throw new InternalError("oursrc is null! - " + (USER_FILE + CLDRDBSRC) + " @ " + ctx.getLocale() );
    		}
    		// Set up checks
    		CheckCLDR checkCldr = (CheckCLDR)uf.getCheck(ctx); //make it happen

    		// Locale menu
    		if((which == null) ||
    				which.equals("")) {
    			which = xMAIN;
    		}



    		if(ctx.hasField(QUERY_EXAMPLE)) {
    			ctx.println("<h3>"+ctx.getLocale()+" "+ctx.getLocale().getDisplayName(ctx.displayLocale)+" / " + which + " Example</h3>");
    		} else if(which.equals(R_STEPS)) {
    			// short menu.
    			ctx.includeFragment(STEPSMENU_TOP_JSP);
    		} else {
    			printLocaleTreeMenu(ctx, which);
    		}

    		// check for errors
    		{
    			List checkCldrResult = (List)uf.hash.get(CHECKCLDR_RES+ctx.getEffectiveCoverageLevel());

    			if((checkCldrResult != null) &&  (!checkCldrResult.isEmpty()) && 
    					(/* true || */ (checkCldr != null) && (xMAIN.equals(which))) ) {
    				ctx.println("<div style='border: 1px dashed olive; padding: 0.2em; background-color: cream; overflow: auto;'>");
    				ctx.println("<b>Possible problems with locale:</b><br>");   
    				for (Iterator it3 = checkCldrResult.iterator(); it3.hasNext();) {
    					CheckCLDR.CheckStatus status = (CheckCLDR.CheckStatus) it3.next();
    					try{ 
    						if (!status.getType().equals(status.exampleType)) {
    							String cls = shortClassName(status.getCause());
    							ctx.printHelpLink("/"+cls,"<!-- help with -->"+cls, true);
    							ctx.println(": ");
    							printShortened(ctx, status.toString(), LARGER_MAX_CHARS);
    							ctx.print("<br>");
    						} else {
    							ctx.println("<i>example available</i><br>");
    						}
    					} catch(Throwable t) {
    						String result;
    						try {
    							result = status.toString();
    						} catch(Throwable tt) {
    							tt.printStackTrace();
    							result = "(Error reading error: " + tt+")";
    						}
    						ctx.println("Error reading status item: <br><font size='-1'>"+result+"<br> - <br>" + t.toString()+"<hr><br>");
    					}
    				}
    				ctx.println("</div>");
    			}
    		}

    		// Find which pod they want, and show it.
    		// NB keep these sections in sync with DataPod.xpathToPodBase() 
    		WebContext subCtx = (WebContext)ctx.clone();
    		subCtx.addQuery(QUERY_LOCALE,ctx.getLocale().toString());
    		subCtx.addQuery(QUERY_SECTION,which);
    		for(int n =0 ; n < PathUtilities.LOCALEDISPLAYNAMES_ITEMS.length; n++) {        
    			if(PathUtilities.LOCALEDISPLAYNAMES_ITEMS[n].equals(which)) {
    				if(which.equals(PathUtilities.CURRENCIES)) {
    					showPathList(subCtx, "//ldml/"+PathUtilities.NUMBERSCURRENCIES, null);
    				} else if(which.equals(PathUtilities.TIMEZONES)) {
    					try {
    						showTimeZones(subCtx);
    					} catch(Throwable t) {
    						t.printStackTrace();
    						SurveyLog.logger.warning("Err showing timezones: " + t);                    		
    						ctx.println("Error: " + t.toString());
    					}
    				} else {
    					showLocaleCodeList(subCtx, which);
    				}
    				return;
    			}
    		}

    		for(int j=0;j<CALENDARS_ITEMS.length;j++) {
    			if(CALENDARS_ITEMS[j].equals(which)) {
    				String CAL_XPATH = "//ldml/dates/calendars/calendar[@type=\""+which+"\"]";
    				showPathList(subCtx, CAL_XPATH, null);
    				return;
    			}
    		}

    		for(int j=0;j<METAZONES_ITEMS.length;j++) {
    			if(METAZONES_ITEMS[j].equals(which)) {
    				showMetazones(subCtx,which);
    				return;
    			}
    		}

    		for(int j=0;j<OTHERROOTS_ITEMS.length;j++) {
    			if(OTHERROOTS_ITEMS[j].equals(which)) {
    				if(which.equals(GREGORIAN_CALENDAR)) {
    					showPathList(subCtx, GREGO_XPATH, null);
    				} else if(which.equals(OTHER_CALENDARS)) {
    					showPathList(subCtx, PathUtilities.OTHER_CALENDARS_XPATH, null);
    				} else if(which.equals(LDMLConstants.LOCALEDISPLAYPATTERN)) {
    					showPathList(subCtx, PathUtilities.LOCALEDISPLAYPATTERN_XPATH, null);
    				} else if(which.equals("units")) {
    					showPathList(subCtx, "//ldml/units", null);
    				} else if(PathUtilities.xOTHER.equals(which)) {
    					showPathList(subCtx, "//ldml", null);
    				} else if(which.equals(LDMLConstants.CHARACTERS)) {
    					showPathList(subCtx, "//ldml/"+LDMLConstants.CHARACTERS, LDMLConstants.EXEMPLAR_CHARACTERS);
    				} else {
    					showPathList(subCtx, "//ldml/"+OTHERROOTS_ITEMS[j], null);
    				}
    				return;
    			}
    		}


    		// fall through if wasn't one of the other roots
    		if(RAW_MENU_ITEM.equals(which)) {
    			doRaw(subCtx);
    		} else if(which.startsWith(REPORT_PREFIX)) {
    			doReport(subCtx, which);
    		} else {
    			doMain(subCtx);
    		}
    	}
    }
    
    private static Pattern reportSuffixPattern = Pattern.compile("^[0-9a-z]([0-9a-z_]*)$");
    /**
     * Is this a legal report suffix? Must contain one or more of [0-9a-z].
     * @param suffix The suffix (not including r_)
     * @return true if legal.
     */
    public static final boolean isLegalReportSuffix(String suffix) {
        return reportSuffixPattern.matcher(suffix).matches();
    }
    
    /**
     * Show a 'report' template (r_)
     * @param ctx context- preset with Locale
     * @param which current section
     */
    public void doReport(WebContext ctx, String which) {
        if(isLegalReportSuffix(which.substring(2))) {
            ctx.flush();
            ctx.includeFragment(which+".jsp");
        } else {
            ctx.println("<i>Illegal report name: " + which+"</i><br/>");
            doMain(ctx);
        }
    }
    

    public void doRaw(WebContext ctx) {
        ctx.println("raw not supported currently. ");
    /*
        CLDRFile file = (CLDRFile)ctx.getByLocale(USER_FILE);
        
        ctx.println("<h3>Raw output of the locale's CLDRFile</h3>");
        ctx.println("<pre style='border: 2px solid olive; margin: 1em;'>");
        
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        file.write(pw);
        String asString = sw.toString();
        //                fullBody = fullBody + "-------------" + "\n" + k + ".xml - " + displayName + "\n" + 
        //                    hexXML.transliterate(asString);
        String asHtml = TransliteratorUtilities.toHTML.transliterate(asString);
        ctx.println(asHtml);
        ctx.println("</pre>");*/
    }

    public static final String XML_PREFIX="/xml/main";
    public static final String ZXML_PREFIX="/zxml/main";
    public static final String ZVXML_PREFIX="/zvxml/main";
    public static final String VXML_PREFIX="/vxml/main";
    public static final String TXML_PREFIX="/txml/main";
    public static final String RXML_PREFIX="/rxml/main";
    public static final String FXML_PREFIX="/fxml/main";
    public static final String FEED_PREFIX="/feed";
    
    private int doOutput(String kind) throws InternalError {
        boolean vetted=false;
        boolean resolved=false;
        boolean users=false;
        boolean translators=false;
        boolean sql = false;
        boolean data=false;
        String lastfile = null;
        String ourDate = SurveyMain.formatDate(); // canonical date
        int nrOutFiles = 0;
        if(kind.equals("sql")) {
            sql= true;
        } else if(kind.equals("xml")) {
            vetted = false;
            data=true;
            resolved = false;
        } else if(kind.equals("txml")) {
            vetted = false;
            data=true;
            resolved = false;
        } else if(kind.equals("vxml")) {
            vetted = true;
            data=true;
            resolved = false;
        } else if(kind.equals("rxml")) {
            vetted = true;
            data=true;
            resolved = true;
        } else if(kind.equals("users")) {
            users = true;
        } else if(kind.equals("usersa")) {
            users = true;
        } else if(kind.equals("translators")) {
            translators = true;
        } else {
            throw new IllegalArgumentException("unknown output: " + kind);
        }
        File outdir = new File(vetdir, kind);
        File voteDir = null;
        if(data) voteDir = new File(outdir, "votes");
        if(outdir.exists() && outdir.isDirectory() && !(isCacheableKind(kind))) {
            File backup = new File(vetdir, kind+".old");
            
            // delete backup
            if(backup.exists() && backup.isDirectory()) {
                File backupVoteDir = new File(backup, "votes");
                if(backupVoteDir.exists()) {
                    File cachedBFiles[] = backupVoteDir.listFiles();
                    if(cachedBFiles != null) {
                        for(File f : cachedBFiles) {
                            if(f.isFile()) {
                                f.delete();
                            }
                        }
                    }
                    backupVoteDir.delete();
                }
                File cachedFiles[] = backup.listFiles();
                if(cachedFiles != null) {
                    for(File f : cachedFiles) {
                        if(f.isFile()) {
                            f.delete();
                        }
                    }
                }
                if(!backup.delete()) {
                    throw new InternalError("Can't delete backup: " + backup.getAbsolutePath());
                }
            }
            
            if(!outdir.renameTo(backup)) {
                throw new InternalError("Can't move outdir " + outdir.getAbsolutePath() + " to backup " + backup);
            }
        }
        
        if(!outdir.exists() && !outdir.mkdir()) {
            throw new InternalError("Can't create outdir " + outdir.getAbsolutePath());
        }
        if(voteDir!=null && !voteDir.exists() && !voteDir.mkdir()) {
            throw new InternalError("Can't create voteDir " + voteDir.getAbsolutePath());
        }
        
        SurveyLog.logger.warning("Writing " + kind);
        long lastTime = System.currentTimeMillis();
        long countStart = lastTime;
        if(sql) {
        	throw new InternalError("Not supported: sql dump");
        } else if(users) {
            boolean obscured = kind.equals("usersa");
            File outFile = new File(outdir,kind+".xml");
            try {
                reg.writeUserFile(this, ourDate, obscured, outFile);
            } catch (IOException e) {
                e.printStackTrace();
                throw new InternalError("writing " + kind + " - IO Exception "+e.toString());
            }
            nrOutFiles++;
        } else if(translators) {
            File outFile = new File(outdir,"cldr-translators.txt");
            //Connection conn = null;
            try {
            	writeTranslatorsFile(outFile);
            } catch (IOException e) {
                e.printStackTrace();
                throw new InternalError("writing " + kind + " - IO Exception "+e.toString());
            } 
            nrOutFiles++;
        } else {
            File inFiles[] = getInFiles();
            int nrInFiles = inFiles.length;
            CLDRProgressTask progress = openProgress("writing " + kind, nrInFiles+1);
            try {
                nrOutFiles = writeDataFile(kind, vetted, resolved, ourDate,
						nrOutFiles, outdir, voteDir, lastTime, countStart,
						inFiles, nrInFiles, progress);
                
                
            } catch(SQLException se) {
            	busted("Writing files:"+kind,se);
            	throw new RuntimeException("Writing files:"+kind,se);
            } catch(IOException se) {
            	busted("Writing files:"+kind,se);
            	throw new RuntimeException("Writing files:"+kind,se);
            } finally {
                progress.close();
            }
        }
        SurveyLog.logger.warning("output: " + kind + " - DONE, files: " + nrOutFiles);
        return nrOutFiles;
    }
    
    public enum CacheableKinds {
    	vxml, xml, rxml, fxml
    };
	/**
	 * @param kind
	 * @return true if this kind is cacheable
	 */
	public static boolean isCacheableKind(String kind) {
    	try {
    		CacheableKinds.valueOf(kind);
    		return true;
    	} catch(IllegalArgumentException iae) {
    		return false;
    	}
	}
	/**
	 * @param kind
	 * @param vetted
	 * @param resolved
	 * @param ourDate
	 * @param nrOutFiles
	 * @param outdir
	 * @param voteDir
	 * @param lastTime
	 * @param countStart
	 * @param inFiles
	 * @param nrInFiles
	 * @param progress
	 * @return
	 * @throws InternalError
	 * @throws SQLException 
	 * @throws IOException 
	 */
	private int writeDataFile(String kind, boolean vetted, boolean resolved,
			String ourDate, int nrOutFiles, File outdir, File voteDir,
			long lastTime, long countStart, File[] inFiles, int nrInFiles,
			CLDRProgressTask progress) throws InternalError, IOException, SQLException {
		String lastfile;
		//Set<Integer> xpathSet = new TreeSet<Integer>();
		boolean xpathSet[] = new boolean[0];
		Connection conn = dbUtils.getDBConnection();
		for(int i=0;(i<nrInFiles) && !SurveyThread.shouldStop();i++) {
		    String fileName = inFiles[i].getName();
		    int dot = fileName.indexOf('.');
		    String localeName = fileName.substring(0,dot);
		    progress.update(i,localeName);
		    lastfile = fileName;
		    File outFile = new File(outdir, fileName);
		    CLDRLocale loc = CLDRLocale.getInstance(localeName);
		    if(isCacheableKind(kind)) {
		    	getOutputFile(conn,loc,kind);
				continue; // use cache
		    }
		    
		    // if i>5 break [ for testing ]
		    
		    XMLSource dbSource = makeDBSource( loc, vetted, resolved);
		    CLDRFile file = makeCLDRFile(dbSource).setSupplementalDirectory(supplementalDataDir);

		    long nextTime = System.currentTimeMillis();
		    if((nextTime - lastTime) > 10000) { // denote, every 10 seconds
		        lastTime = nextTime;
		        SurveyLog.logger.warning("output: " + kind + " / " + localeName + ": #"+i+"/"+nrInFiles+", or "+
		            (((double)(System.currentTimeMillis()-countStart))/i)+"ms per.");
		    }
		    
		    if(!kind.equals("txml")) {
			    try {
			        PrintWriter utf8OutStream = new PrintWriter(
			            new OutputStreamWriter(
			                new FileOutputStream(outFile), "UTF8"));
			        synchronized(this.vet) {
			        	file.write(utf8OutStream);
			        }
			        nrOutFiles++;
			        utf8OutStream.close();
			        lastfile = null;
	      //            } catch (UnsupportedEncodingException e) {
	      //                throw new InternalError("UTF8 unsupported?").setCause(e);
			    } catch (IOException e) {
			        e.printStackTrace();
			        throw new InternalError("IO Exception "+e.toString());
			    } finally {
			        if(lastfile != null) {
			            SurveyLog.logger.warning("Last file written: " + kind + " / " + lastfile);
			        }
			    }
		    }
		    lastfile = fileName + " - vote data";
		    // write voteFile
		    File voteFile = new File(voteDir,fileName);
		    try {
		        PrintWriter utf8OutStream = new PrintWriter(
		            new OutputStreamWriter(
		                new FileOutputStream(voteFile), "UTF8"));
		        boolean NewxpathSet[] = this.vet.writeVoteFile(utf8OutStream, conn, dbSource, file, ourDate, xpathSet);
		        nrOutFiles++;
		        utf8OutStream.close();
		        lastfile = null;
		        if(NewxpathSet==null) {
		        	voteFile.delete();
		        } else {
		        	xpathSet=NewxpathSet;
		        }
      //            } catch (UnsupportedEncodingException e) {
      //                throw new InternalError("UTF8 unsupported?").setCause(e);
		    } catch (IOException e) {
		        e.printStackTrace();
		        throw new InternalError("IO Exception on vote file "+e.toString());
		    } catch (SQLException e) {
		        // TODO Auto-generated catch block
		        e.printStackTrace();
		        throw new InternalError("SQL Exception on vote file "+DBUtils.unchainSqlException(e));
		    } finally {
		        if(lastfile != null) {
		            SurveyLog.logger.warning("Last  vote file written: " + kind + " / " + lastfile);
		        }
		    }

		}
		DBUtils.closeDBConnection(conn);

		progress.update(nrInFiles, "writing " +"xpathTable");
		lastfile = "xpathTable.xml" + " - xpath table";
		// write voteFile
		File xpathFile = new File(voteDir,"xpathTable.xml");
		SurveyLog.logger.warning("Writting xpath @ " + voteDir.getAbsolutePath());
		try {
		    PrintWriter utf8OutStream = new PrintWriter(
		        new OutputStreamWriter(
		            new FileOutputStream(xpathFile), "UTF8"));
		    xpt.writeXpaths(utf8OutStream, ourDate, xpathSet);
		    nrOutFiles++;
		    utf8OutStream.close();
		    lastfile = null;
   //            } catch (UnsupportedEncodingException e) {
   //                throw new InternalError("UTF8 unsupported?").setCause(e);
		} catch (IOException e) {
		    e.printStackTrace();
		    throw new InternalError("IO Exception on vote file "+e.toString());
		} finally {
		    if(lastfile != null) {
		        SurveyLog.logger.warning("Last  vote file written: " + kind + " / " + lastfile);
		    }
		}
		return nrOutFiles;
	}
	/**
	 * @param outFile
	 * @return 
	 * @throws UnsupportedEncodingException
	 * @throws FileNotFoundException
	 */
	private int writeTranslatorsFile(File outFile)
			throws UnsupportedEncodingException, FileNotFoundException {
		Connection conn;
		conn = dbUtils.getDBConnection();
		PrintWriter out = new PrintWriter(
		    new OutputStreamWriter(
		        new FileOutputStream(outFile), "UTF8"));
   //        ctx.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
   //        ctx.println("<!DOCTYPE ldml SYSTEM \"http://.../.../stusers.dtd\">");
   //        ctx.println("<users host=\""+ctx.serverHostport()+"\">");
		String org = null;
		try { synchronized(reg) {
		    java.sql.ResultSet rs = reg.list(org, conn);
		    if(rs == null) {
		        out.println("# No results");
		        return 0;
		    }
		    while(rs.next()) {
		        int theirId = rs.getInt(1);
		        int theirLevel = rs.getInt(2);
		        String theirName = DBUtils.getStringUTF8(rs, 3);//rs.getString(3);
		        String theirEmail = rs.getString(4);
		        String theirOrg = rs.getString(5);
		        String theirLocales = rs.getString(6);
		        
		        if(theirLevel >= UserRegistry.LOCKED) {
		            continue;
		        }
		        
		        out.println(theirEmail);//+" : |NOPOST|");
		      /*
		        ctx.println("\t<user id=\""+theirId+"\" email=\""+theirEmail+"\">");
		        ctx.println("\t\t<level n=\""+theirLevel+"\" type=\""+UserRegistry.levelAsStr(theirLevel)+"\"/>");
		        ctx.println("\t\t<name>"+theirName+"</name>");
		        ctx.println("\t\t<org>"+theirOrg+"</org>");
		        ctx.println("\t\t<locales type=\"edit\">");
		        String theirLocalesList[] = UserRegistry.tokenizeLocale(theirLocales);
		        for(int i=0;i<theirLocalesList.length;i++) {
		            ctx.println("\t\t\t<locale id=\""+theirLocalesList[i]+"\"/>");
		        }
		        ctx.println("\t\t</locales>");
		        ctx.println("\t</user>");
		       */
		    }            
		} } catch(SQLException se) {
		    SurveyLog.logger.log(java.util.logging.Level.WARNING,"Query for org " + org + " failed: " + DBUtils.unchainSqlException(se),se);
		    out.println("# Failure: " + DBUtils.unchainSqlException(se) + " -->");
		}finally {
				DBUtils.close(conn);
		}
		out.close();
		return 1;
		
	}
	public synchronized boolean doRawXml(HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException {
        String s = request.getPathInfo();
        
        if((s==null)||!(s.startsWith(XML_PREFIX)||s.startsWith(ZXML_PREFIX)||s.startsWith(ZVXML_PREFIX)||s.startsWith(VXML_PREFIX)||
        		s.startsWith(FXML_PREFIX) ||
        			s.startsWith(RXML_PREFIX)||s.startsWith(TXML_PREFIX)||s.startsWith(FEED_PREFIX))) {
            return false;
        }
        
        if(s.startsWith(FEED_PREFIX)) {
            return fora.doFeed(request, response);
        }
        
        CLDRProgressTask progress = this.openProgress("Raw XML");
        Connection conn = null;
        try {
        	
        boolean finalData = false;
        boolean resolved = false;
        boolean voteData = false;
        boolean cached = false;
        String kind = null;
        
        if(s.startsWith(VXML_PREFIX)) {
            finalData = true;
            if(s.equals(VXML_PREFIX)) {
                WebContext ctx = new WebContext(request,response);
                response.sendRedirect(ctx.schemeHostPort()+ctx.base()+VXML_PREFIX+"/");
                return true;
            }
            kind="vxml";
            s = s.substring(VXML_PREFIX.length()+1,s.length()); //   "foo.xml"
        } else if(s.startsWith(RXML_PREFIX)) {
            finalData = true;
            resolved=true;

            if(s.equals(RXML_PREFIX)) {
                WebContext ctx = new WebContext(request,response);
                response.sendRedirect(ctx.schemeHostPort()+ctx.base()+RXML_PREFIX+"/");
                return true;
            }
            kind="rxml"; // cached
            s = s.substring(RXML_PREFIX.length()+1,s.length()); //   "foo.xml"
        } else if(s.startsWith(FXML_PREFIX)) {
            finalData = true;

            if(s.equals(FXML_PREFIX)) {
                WebContext ctx = new WebContext(request,response);
                response.sendRedirect(ctx.schemeHostPort()+ctx.base()+FXML_PREFIX+"/");
                return true;
            }
            kind="fxml"; // cached
            s = s.substring(FXML_PREFIX.length()+1,s.length()); //   "foo.xml"
        } else if(s.startsWith(TXML_PREFIX)) {
            finalData = true;
            resolved=false;
            voteData = true;

            if(s.equals(TXML_PREFIX)) {
                WebContext ctx = new WebContext(request,response);
                response.sendRedirect(ctx.schemeHostPort()+ctx.base()+TXML_PREFIX+"/");
                return true;
            }
            s = s.substring(TXML_PREFIX.length()+1,s.length()); //   "foo.xml"
        } else if(s.startsWith(ZXML_PREFIX)) {
            finalData = false;
            resolved=false;
            voteData = false;
            cached = true;
            s = s.substring(ZXML_PREFIX.length()+1,s.length()); //   "foo.xml"
        } else if(s.startsWith(ZVXML_PREFIX)) {
            finalData = true;
            resolved=false;
            voteData = false;
            cached = true;
            s = s.substring(ZVXML_PREFIX.length()+1,s.length()); //   "foo.xml"
        } else {
            if(s.equals(XML_PREFIX)) {
                WebContext ctx = new WebContext(request,response);
                response.sendRedirect(ctx.schemeHostPort()+ctx.base()+XML_PREFIX+"/");
                return true;
            }
            kind="xml";
            s = s.substring(XML_PREFIX.length()+1,s.length()); //   "foo.xml"
        }
        
        if(s.length() == 0) {
            WebContext ctx = new WebContext(request,response);
            response.setContentType("text/html; charset=utf-8");
            if(finalData) {
                ctx.println("<title>CLDR Data | All Locales - Vetted Data</title>");
            } else {
                ctx.println("<title>CLDR Data | All Locales</title>");
            }
            ctx.println("<a href='"+ctx.base()+"'>Return to SurveyTool</a><p>");
            ctx.println("<h4>Locales</h4>");
            ctx.println("<ul>");
            File inFiles[] = getInFiles();
            int nrInFiles = inFiles.length;
            for(int i=0;i<nrInFiles;i++) {
                String fileName = inFiles[i].getName();
                int dot = fileName.indexOf('.');
                String localeName = fileName.substring(0,dot);
                ctx.println("<li><a href='"+fileName+"'>"+fileName+"</a> " + new ULocale(localeName).getDisplayName(ctx.displayLocale) +
                    "</li>");
            }
            ctx.println("</ul>");
            ctx.println("<hr>");
            ctx.println("<a href='"+ctx.base()+"'>Return to SurveyTool</a><p>");
            ctx.close();
        } else if(!s.endsWith(".xml")) {
            WebContext ctx = new WebContext(request,response);
            response.sendRedirect(ctx.schemeHostPort()+ctx.base()+XML_PREFIX+"/");
        } else {
            File inFiles[] = getInFiles();
            int nrInFiles = inFiles.length;
            boolean found = false;
            String theLocale = null;
            for(int i=0;(!found) && (i<nrInFiles);i++) {
                String localeName = inFiles[i].getName();
                if(s.equals(localeName)) {
                    found=true;
                    theLocale = fileNameToLocale(localeName).getBaseName();
                }
            }
            if(!found) {
                throw new InternalError("No such locale: " + s);
            } else synchronized(this.vet) {
		String doKvp = request.getParameter("kvp");
		boolean isKvp = (doKvp!=null && doKvp.length()>0);

		if(isKvp)  {
			response.setContentType("text/plain; charset=utf-8");
		} else {
                	response.setContentType("application/xml; charset=utf-8");
		}
		
        CLDRLocale locale = CLDRLocale.getInstance(theLocale);

		if(kind!=null ) {
			try {
				File f = getOutputFile(locale,kind);
				FileInputStream fis = new FileInputStream(f);
				byte buf[] = new byte[2048];
				int count=0;
				ServletOutputStream out = response.getOutputStream();
				while((count=fis.read(buf))>=0) {
					out.write(buf, 0, count);
				}
				fis.close();
				return true;
			} catch (SQLException e) {
				e.printStackTrace();
				throw new RuntimeException(DBUtils.unchainSqlException(e));
			}
		}
		
                XMLSource dbSource = null; 
                CLDRFile file;
                if(cached == true) {
                    if(finalData) {
                        file = this.getCLDRFileCache().getVettedCLDRFile(locale);
                    } else { 
                        file = this.getCLDRFileCache().getCLDRFile(locale, resolved);
                    }
                } else {
//                    conn = getDBConnection();
                    file = new CLDRFile(makeDBSource(locale, finalData, resolved)).setSupplementalDirectory(supplementalDataDir);
                }
    //            file.write(WebContext.openUTF8Writer(response.getOutputStream()));
                if(voteData) {
                    try {
                        vet.writeVoteFile(response.getWriter(), conn, dbSource, file, formatDate(), null);
                    } catch (SQLException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                        SurveyLog.logger.warning("<!-- exception: "+e+" -->");
                    }
                } else {
			if(!isKvp) {
                    		file.write(response.getWriter());
			} else {
				// full xpath tab value
				java.io.Writer w = response.getWriter();
				//PrintWriter pw = new PrintWriter(w);
				for(String str : file) {
					String xo = file.getFullXPath(str);
					String v = file.getStringValue(str);

					w.write(xo+"\t"+v+"\n");

				}
						
			}
	
                }
            }
        }
        return true;
        } finally {
        	progress.close();
            DBUtils.closeDBConnection(conn);
        }
    }
	/**
	 * @param localeName
	 * @return
	 */
	private CLDRLocale fileNameToLocale(String localeName) {
		String theLocale;
		int dot = localeName.indexOf('.');
		theLocale = localeName.substring(0,dot);
		return CLDRLocale.getInstance(theLocale);
	}

    /**
    * Show the 'main info about this locale' (General) panel.
     */
    public void doMain(WebContext ctx) {
        //SLOW: String diskVer = LDMLUtilities.loadFileRevision(fileBase, ctx.getLocale().toString() + ".xml"); // just get ver of the latest file.
        String dbVer = getDBSourceFactory().getSourceRevision(ctx.getLocale());
        
        // what should users be notified about?
        if(isPhaseSubmit() || isPhaseVetting() || isPhaseVettingClosed()) {
            CLDRLocale localeName = ctx.getLocale();
            String groupName = localeName.getLanguage();
            int vetStatus = vet.status(localeName);
            
            int genCount = externalErrorCount(localeName);
            if(genCount > 0) {
                ctx.println("<h2>Errors which need your attention:</h2><a href='"+externalErrorUrl(groupName)+"'>Error Count ("+genCount+")</a><p>");
            } else if(genCount < 0) {
                ctx.println("<!-- (error reading the counts file.) -->");
            }
            int orgDisp = 0;
            if(ctx.session.user != null) {
                orgDisp = vet.getOrgDisputeCount(ctx.session.user.voterOrg(),localeName);
                
                if(orgDisp > 0) {
                    
                    ctx.print("<h4><span style='padding: 1px;' class='disputed'>"+(orgDisp)+" items with conflicts among "+ctx.session.user.org+" vetters.</span> "+ctx.iconHtml("disp","Vetter Dispute")+"</h4>");
                    
                    Set<String> disputePaths = vet.getOrgDisputePaths(ctx.session.user.voterOrg(), localeName);
                    Map<String,Set<String>> odItems = new TreeMap<String,Set<String>>();
                    for(String path : disputePaths) {
                    	String theMenu = PathUtilities.xpathToMenu(path);
                    	if(theMenu != null) {
                    		Set<String> paths = odItems.get(theMenu);
                    		if(paths==null) {
                    			paths=new TreeSet<String>();
                    			odItems.put(theMenu,paths);
                    		}
                    		paths.add(path);
                    	}
                    }
                    WebContext subCtx = (WebContext)ctx.clone();
                    //subCtx.addQuery(QUERY_LOCALE,ctx.getLocale().toString());
                    subCtx.removeQuery(QUERY_SECTION);
                    for(Map.Entry<String,Set<String>> e : odItems.entrySet()) {
                        //printMenu(subCtx, "", e.getKey());
                    	ctx.println("<h3>"+e.getKey()+"</h3>");
                        ctx.println("<ol>");
                        for(String path:e.getValue()) {
                        	ctx.println("<li>"+
                        				"<a "+ctx.atarget()+" href='"+
                        					fora.forumUrl(subCtx, ctx.getLocale().toString(), xpt.getByXpath(path))
                        				+"'>" +
                        					xpt.getPrettyPath(path) +
                        					ctx.iconHtml("disp","Vetter Dispute")
                        				+"</a>" +
                        				"</li>");
                        }
                        ctx.println("</ol>");
                    }
                    ctx.println("<br>");
                }
            }
            
            vet.doDisputePage(ctx);
            
            /*  OLD 'disputed need attention' page. */
            if(false && (UserRegistry.userIsVetter(ctx.session.user))&&((vetStatus & Vetting.RES_BAD_MASK)>0)) {
                //int numNoVotes = vet.countResultsByType(ctx.getLocale().toString(),Vetting.RES_NO_VOTES);
                int numInsufficient = vet.countResultsByType(ctx.getLocale(),Vetting.RES_INSUFFICIENT);
                int numDisputed = vet.countResultsByType(ctx.getLocale(),Vetting.RES_DISPUTED);
                int numErrors =  0;//vet.countResultsByType(ctx.getLocale().toString(),Vetting.RES_ERROR);
             
                Hashtable<String,Integer> insItems = new Hashtable<String,Integer>();
                Hashtable<String,Integer> disItems = new Hashtable<String,Integer>();

                try { // moderately expensive.. since we are tying up vet's connection..
                	Connection conn = null;
                	PreparedStatement listBadResults = null;
                	try {
                		conn= dbUtils.getDBConnection();
                		listBadResults = vet.prepare_listBadResults(conn);
                		listBadResults.setString(1, ctx.getLocale().getBaseName());
                		ResultSet rs = listBadResults.executeQuery();
                		while(rs.next()) {
                			int xp = rs.getInt(1);
                			int type = rs.getInt(2);

                			String path = xpt.getById(xp);

                			String theMenu = PathUtilities.xpathToMenu(path);

                			if(theMenu != null) {
                				if(type == Vetting.RES_DISPUTED) {
                					Integer n = disItems.get(theMenu);
                					if(n==null) {
                						n = 1;
                					}
                					disItems.put(theMenu, n+1);// what goes here?
                				} else if (type == Vetting.RES_ERROR) {
                					//disItems.put(theMenu, "");
                				} else {
                					Integer n = insItems.get(theMenu);
                					if(n==null) {
                						n = 1;
                					}
                					insItems.put(theMenu, n+1);// what goes here?
                				}
                			}
                		}
                		rs.close();
                	} finally  {
                		DBUtils.close(listBadResults,conn);
                	}
                } catch (SQLException se) {
                	throw new RuntimeException("SQL error listing bad results - " + DBUtils.unchainSqlException(se));
                }
                // et.tostring

                WebContext subCtx = (WebContext)ctx.clone();
                //subCtx.addQuery(QUERY_LOCALE,ctx.getLocale().toString());
                subCtx.removeQuery(QUERY_SECTION);

               if(false && (this.phase()==Phase.VETTING || isPhaseVettingClosed()) == true) {
                    
                    if((numDisputed>0)||(numErrors>0)) {
                        ctx.print("<h2>Disputed items that need your attention:</h2>");
                        ctx.print("<b>total: "+numDisputed+ " - </b>");
                        for(Iterator li = disItems.keySet().iterator();li.hasNext();) {
                            String item = (String)li.next();
                            int count = disItems.get(item);
                            printMenu(subCtx, "", item, item + "("+count+")", "only=disputed&x", DataSection.CHANGES_DISPUTED);
                            if(li.hasNext() ) {
                                subCtx.print(" | ");
                            }
                        }
                        ctx.println("<br>");
                    }
                    if((/*numNoVotes+*/numInsufficient)>0) {
                        ctx.print("<h2>Unconfirmed items (insufficient votes). Please do if possible.</h2>");
                        ctx.print("<b>total: "+numInsufficient+ " - </b>");
                        for(Iterator li = insItems.keySet().iterator();li.hasNext();) {
                            String item = (String)li.next();
                            int count = insItems.get(item);
                            printMenu(subCtx, "", item, item + "("+count+")");
                            if(li.hasNext() ) {
                                subCtx.print(" | ");
                            }
                        }
                        ctx.println("<br>");
                    }
                }
            }
        }
        
        ctx.println("<hr/><p><p>");
        ctx.println("<h3>Basic information about the Locale</h3>");
        
        // coverage level
        ctx.showCoverageLevel();
    
        
        ctx.print("  <p><i><font size='+1' color='red'>Important Notes:</font></i></p>  <ul>    <li><font size='4'><i>W</i></font><i><font size='4'>"+
                    "hen you navigate away from any page, any     data changes you've made will be lost <b>unless</b> you hit the"+
                    " <b>"+getSaveButtonText()+"</b> button!</font></i></li>    <li><i><font size='4'>"+
                                SLOW_PAGE_NOTICE+
                    "</font></i></li>    <li><i><font size='4'>Be sure to read </font>    "+
    //                "<a href='http://www.unicode.org/cldr/wiki?SurveyToolHelp'>"
                    "<font size='4'>");
        ctx.println("<a href='"+GENERAL_HELP_URL+"'>"+GENERAL_HELP_NAME+"</a>"); // base help
        ctx.print("</font>"+
                    "<font size='4'>     once before going further.</font></i></li>   "+
                    " <!-- <li> <font size='4'><i>Consult the Page Instructions if you have questions on any page.</i></font> "+
                    "</li> --> </ul>");
        
        if(dbVer != null) {
            ctx.println( LDMLUtilities.getCVSLink(ctx.getLocale().toString(), dbVer) + "version #" + dbVer + "</a>");
//            if((diskVer != null)&&(!diskVer.equals(dbVer))) {
//                ctx.println( " " + LDMLUtilities.getCVSLink(ctx.getLocale().toString(), dbVer) + "(Note: version " + diskVer + " is available to the administrator.)</a>");
//            }
        }    
        ctx.println(SLOW_PAGE_NOTICE);
        if(ctx.session.user!=null) {
        	ctx.println("<br>");
        	ctx.println("<a href='"+ctx.jspLink("xpath.jsp")+"&_="+ctx.getLocale().toString()+"'>Go to XPath...</a><br>");
        }
    }

    public static String getAttributeValue(Document doc, String xpath, String attribute) {
        if(doc != null) {
            Node n = LDMLUtilities.getNode(doc, xpath);
            if(n != null) {
                return LDMLUtilities.getAttributeValue(n, attribute);
            }
        }
        return null;
    }

    /**
    private SoftReference nodeHashReference = null;
    int nodeHashPuts = 0;
    private final Hashtable getNodeHash() {
        Hashtable nodeHash = null;
        if((nodeHashReference == null) ||
           ((nodeHash=(Hashtable)nodeHashReference.get())==null)) {
            return null;
        }
        return nodeHash;
    }
    **/

    public static final String USER_FILE = "UserFile";
    public static final String USER_FILE_KEY = "UserFileKey";
    public static final String CLDRDBSRC = "_source";

    private static CLDRFile gBaselineFile = null;
    private static ExampleGenerator gBaselineExample = null;

    private Factory gFactory = null;

    synchronized Factory getDiskFactory() {
        if(gFactory == null) {
        	final String list[] = { fileBase, fileBaseSeed };
            gFactory = SimpleFactory.make(list,".*");
        }
        return gFactory;
    }

    private STFactory gSTFactory = null;

    public final synchronized STFactory getSTFactory() {
        if(gSTFactory == null) {
            gSTFactory = new STFactory(this);
        }
        return gSTFactory;
    }

    /**
     * destroy the ST Factory - testing use only!
     * @internal
     */
    public final synchronized STFactory TESTING_removeSTFactory() {
    	STFactory oldFactory = gSTFactory;
    	gSTFactory = null;
    	return oldFactory;
    }
    
    /**
     * @return the dbsrcfac
     * @deprecated phase out DBSrcFac in favor of getSTFactory
     */
    public CLDRDBSourceFactory getDBSourceFactory() {
        return dbsrcfac;
    }
    /**
     * @param dbsrcfac the dbsrcfac to set
     */
    public void setDBSourceFactory(CLDRDBSourceFactory dbsrcfac) {
        this.dbsrcfac = dbsrcfac;
    }

    private Factory gOldFactory = null;
    
    /**
     * Return the actual XML file on disk
     * @param loc
     * @return
     */
    public File getBaseFile(CLDRLocale loc) {
    	return new File(fileBase,loc.getBaseName()+".xml");
    }

    synchronized Factory getOldFactory() {
        if(gOldFactory == null) {
            File oldBase = new File(getFileBaseOld());
            File oldCommon = new File(oldBase,"common/main");
            if(!oldCommon.isDirectory()) {
                String verAsMilestone = "release-"+oldVersion.replaceAll("\\.", "-");
                String msg = ("Could not read old data - " + oldCommon.getAbsolutePath() + ": you might do 'svn export http://unicode.org/repos/cldr/tags/"+verAsMilestone + "/common "+ oldBase.getAbsolutePath() + "/common' - or check " + getOldVersionParam() + " and CLDR_OLDVERSION parameters. ");
                //svn export http://unicode.org/repos/cldr/tags/release-1-8 1.8
                
                busted(msg);
                throw new InternalError(msg);
            }
            File oldSeed = new File(oldBase,"seed/main");
            if(!oldSeed.isDirectory()) {
                String verAsMilestone = "release-"+oldVersion.replaceAll("\\.", "-");
                String msg = ("Could not read old seed data - " + oldSeed.getAbsolutePath() + ": you might do 'svn export http://unicode.org/repos/cldr/tags/"+verAsMilestone + "/seed "+ oldBase.getAbsolutePath() + "/seed' - or check " + getOldVersionParam() + " and CLDR_OLDVERSION parameters. ");
                //svn export http://unicode.org/repos/cldr/tags/release-1-8 1.8
                
                busted(msg);
                throw new InternalError(msg);
            }
            String roots[] = { oldCommon.getAbsolutePath(), oldSeed.getAbsolutePath() };
            gOldFactory = SimpleFactory.make(roots,".*");
        }
        return gOldFactory;
    }

    public synchronized CLDRFile getBaselineFile(/*CLDRDBSource ourSrc*/) {
        if(gBaselineFile == null) {
            try {
                CLDRFile file = getDiskFactory().make(BASELINE_LOCALE.toString(), true);
                file.setSupplementalDirectory(supplementalDataDir); // so the icuServiceBuilder doesn't blow up.
                file.freeze(); // so it can be shared.
                gBaselineFile = file;
            } catch (Throwable t) {
                busted("Could not load baseline locale " + BASELINE_LOCALE, t);
            }
        }
        return gBaselineFile;
    }
    
    HashMap<String,String> gBaselineHash = new HashMap<String,String>();

	Set<UserLocaleStuff> allUserLocaleStuffs = new HashSet<UserLocaleStuff>();

    /* Sentinel value indicating that there was no baseline string available. */
    private static final String NULL_STRING = "";

	public static final String DATAROW_JSP = "datarow_jsp";  // context tag for which datarow jsp to use

	public static final String DATAROW_JSP_DEFAULT = "datarow_short.jsp";

	public static final String QUERY_VALUE_SUFFIX = "_v";

    public synchronized String baselineFileGetStringValue(String xpath) {
        String res = gBaselineHash.get(xpath);
        if(res == null) {
            res = getBaselineFile().getStringValue(xpath);
            if(res == null) {
                res = NULL_STRING;
            }
            gBaselineHash.put(xpath,res);
        }
        if(res == NULL_STRING) {
            return null;
        } else {
            return res;
        }
    }

    public synchronized ExampleGenerator getBaselineExample() {
        if(gBaselineExample == null) {
            CLDRFile baselineFile = getBaselineFile();
            gBaselineExample = new ExampleGenerator(baselineFile, baselineFile, fileBase + "/../supplemental/");
        }
        gBaselineExample.setVerboseErrors(twidBool("ExampleGenerator.setVerboseErrors"));
        return gBaselineExample;
    }

    public synchronized WebContext.HTMLDirection getHTMLDirectionFor(CLDRLocale locale) {
        String dir = getDirectionalityFor(locale);
        return HTMLDirection.fromCldr(dir);
    }
    
    public synchronized String getDirectionalityFor(CLDRLocale id) {
        final boolean DDEBUG=false;
        if (DDEBUG) SurveyLog.logger.warning("Checking directionality for " + id);
        if(aliasMap==null) {
            checkAllLocales();
        }
        while(id != null) {
            // TODO use iterator
            CLDRLocale aliasTo = isLocaleAliased(id);
            if (DDEBUG) SurveyLog.logger.warning("Alias -> "+aliasTo);
            if(aliasTo != null 
                    && !aliasTo.equals(id)) { // prevent loops
                id = aliasTo;
                if (DDEBUG) SurveyLog.logger.warning(" -> "+id);
                continue;
            }
            String dir = directionMap.get(id);
            if (DDEBUG) SurveyLog.logger.warning(" dir:"+dir);
            if(dir!=null) {
                return dir;
            }
            id = id.getParent();
            if (DDEBUG) SurveyLog.logger.warning(" .. -> :"+id);
        }
        if (DDEBUG) SurveyLog.logger.warning("err: could not get directionality of root");
        return "left-to-right"; //fallback
    }

    
    /**
     * Returns the current basic options map.
     * @return the map
     * @see org.unicode.cldr.test.CheckCoverage#check(String, String, String, Map, List)
     */
    public static Map basicOptionsMap() {
        Map options = new HashMap();
        
        // the following is highly suspicious. But, CheckCoverage seems to require it.
        options.put("submission", "true");
        // options.put("CheckCoverage.requiredLevel", "minimal");
        
        // pass in the current ST phase
        if(isPhaseVetting() || isPhaseVettingClosed()) {
            options.put("phase", "vetting");
        } else if(isPhaseSubmit()) {
            options.put("phase", "submission");
        } else if(isPhaseFinalTesting()) {
            options.put("phase", "final_testing");
        }
        
        return options;
    }

    public CheckCLDR createCheck() {
            CheckCLDR checkCldr;
            
//                logger.info("Initting tests . . . - "+ctx.getLocale()+"|" + ( CHECKCLDR+":"+ctx.defaultPtype()) + "@"+ctx.session.id);
//            long t0 = System.currentTimeMillis();
            
            // make sure CLDR has the latest display information.
            //if(phaseVetting) {
            //    checkCldr = CheckCLDR.getCheckAll("(?!.*(DisplayCollisions|CheckCoverage).*).*" /*  ".*" */);
            //} else {
            checkCldr = CheckCLDR.getCheckAll(getSTFactory(), "(?!.*(CheckCoverage).*).*");
//                checkCldr = CheckCLDR.getCheckAll("(?!.*DisplayCollisions.*).*" /*  ".*" */);
            //}

            checkCldr.setDisplayInformation(getBaselineFile());
            
            return checkCldr;
    }

    public CheckCLDR createCheckWithoutCollisions() {
            CheckCLDR checkCldr;
            
//                logger.info("Initting tests . . . - "+ctx.getLocale()+"|" + ( CHECKCLDR+":"+ctx.defaultPtype()) + "@"+ctx.session.id);
//            long t0 = System.currentTimeMillis();
            
            // make sure CLDR has the latest display information.
            //if(phaseVetting) {
            //    checkCldr = CheckCLDR.getCheckAll("(?!.*(DisplayCollisions|CheckCoverage).*).*" /*  ".*" */);
            //} else {
            if(false) {  // show ALL ?
                checkCldr = CheckCLDR.getCheckAll(getSTFactory(), ".*");
            } else {
                checkCldr = CheckCLDR.getCheckAll(getSTFactory(), "(?!.*(DisplayCollisions|CheckCoverage).*).*" /*  ".*" */);
            }

            checkCldr.setDisplayInformation(getBaselineFile());
            
            return checkCldr;
    }

    public static boolean CACHE_VXML_FOR_TESTS = false;
//    public static boolean CACHE_VXML_FOR_EXAMPLES = false;

    /**
     * Any user of this should be within session sync.
     * @author srl
     *
     */
    public class UserLocaleStuff extends Registerable {
    	private DBEntry dbEntry = null;
        public CLDRFile cldrfile = null;
        private CLDRFile cachedCldrFile = null; /* If not null: use this for tests. Readonly. */
        public XMLSource dbSource = null;
        public XMLSource resolvedSource = null;
        public Hashtable hash = new Hashtable();
        private ExampleGenerator exampleGenerator = null;
        private Registerable exampleIsValid = new Registerable(lcr, locale);
		private int use;
		CLDRFile resolvedFile = null;
		CLDRFile baselineFile;

		public void open() {
			use++;
			SurveyLog.logger.warning("uls: open="+use);
		}
        
        public ExampleGenerator getExampleGenerator() {
        	if(exampleGenerator==null || !exampleIsValid.isValid() ) {
        		//                if(CACHE_VXML_FOR_EXAMPLES) {
        		//                    fileForGenerator = getCLDRFileCache().getCLDRFile(locale, true);
        		//                } else {
        		//   SurveyLog.logger.warning("!CACHE_VXML_FOR_EXAMPLES");
        		//              }

        		if(resolvedFile==null) {
        			SurveyLog.logger.warning("Err: fileForGenerator is null for " + dbSource);
        		}
        		
        		if(resolvedFile.getSupplementalDirectory()==null) {
        			throw new InternalError("?!!! resolvedFile has no supplemental dir.");
        		}
        		if(baselineFile.getSupplementalDirectory()==null) {
        			throw new InternalError("?!!! baselineFile has no supplemental dir.");
        		}
        		
        		exampleGenerator = new ExampleGenerator(resolvedFile, baselineFile, fileBase + "/../supplemental/");
        		exampleGenerator.setVerboseErrors(twidBool("ExampleGenerator.setVerboseErrors"));
        		//SurveyLog.logger.warning("-revalid exgen-"+locale + " - " + exampleIsValid + " in " + this);
        		exampleIsValid.setValid();
        		//SurveyLog.logger.warning(" >> "+locale + " - " + exampleIsValid + " in " + this);
        		exampleIsValid.register();
        		//SurveyLog.logger.warning(" >>> "+locale + " - " + exampleIsValid + " in " + this);
        	}
            return exampleGenerator;
        }
        
        private String closeStack = null;
        
        public void close() {
        	if(use<=0) {
        		throw new InternalError("Already closed! use="+use+", closeStack:"+closeStack);
        	}
			use--;
			closeStack = DEBUG?StackTracker.currentStack():null;
			SurveyLog.logger.warning("uls: close="+use);
        	if(use>0) {
        		return;
        	}
        	internalClose();
			// TODO Auto-generated method stub
            synchronized(allUserLocaleStuffs) {
            	allUserLocaleStuffs.remove(this);
            }
		}
        
        public void internalClose() {
            this.dbSource=null;
            try {
                if(this.dbEntry!=null) {
                    this.dbEntry.close();
                }
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        
        public boolean isClosed() {
        	return this.dbSource==null;
        }

		public UserLocaleStuff(CLDRLocale locale) {
            super(lcr, locale);
            exampleIsValid.register();
//    SurveyLog.logger.warning("Adding ULS:"+locale);
            synchronized(allUserLocaleStuffs) {
            	allUserLocaleStuffs.add(this);
            }
            complete(locale);
        }
        
        public void clear() {
            hash.clear();        
            // TODO: try just kicking these instead of clearing?
            cldrfile=null;
            dbSource=null;
            hash.clear();
            setValid();
        }
        
        public CheckCLDR getCheck(String ptype, Map<String,String> options) {
            CheckCLDR checkCldr = (CheckCLDR)hash.get(CHECKCLDR+ptype);
            if (checkCldr == null)  {
                List checkCldrResult = new ArrayList();
                
                checkCldr = createCheck();
                
                //long t0 = System.currentTimeMillis();
                checkCldr.setCldrFileToCheck(cldrfile, options, checkCldrResult);
                //   logger.info("fileToCheck set . . . on "+ checkCldr.toString());
                hash.put(CHECKCLDR+ptype, checkCldr);
                if(!checkCldrResult.isEmpty()) {
                    hash.put(CHECKCLDR_RES+ptype, checkCldrResult);
                }
                //long t2 = System.currentTimeMillis();
                //logger.info("Time to init tests: " + (t2-t0));
            }
            return checkCldr;
        }
        public CheckCLDR getCheck(WebContext ctx) {
        	return getCheck(ctx.getEffectiveCoverageLevel(), ctx.getOptionsMap(basicOptionsMap()));
        }
        
        
        CLDRFile makeCachedCLDRFile(XMLSource dbSource) {
            if(CACHE_VXML_FOR_TESTS) {
                return getCLDRFileCache().getCLDRFile(locale());
            } else {
//                SurveyLog.logger.warning(" !CACHE_VXML_FOR_TESTS");
                return null;
            }
        }

        /**
         * 
         * @param ctx
         * @param user
         * @param locale
         */
        private void complete(CLDRLocale locale) {
            // TODO: refactor.
            if(cldrfile == null) {
                resolvedSource = getSTFactory().makeSource(locale.getBaseName(),true);
                dbSource = resolvedSource.getUnresolving();
                cldrfile = getSTFactory().make(locale,true).setSupplementalDirectory(supplementalDataDir);
//                cachedCldrFile = makeCachedCLDRFile(dbSource);
        		resolvedFile = cldrfile;
        		//XMLSource baseSource = makeDBSource(CLDRLocale.getInstance(BASELINE_LOCALE), false, true);
        		baselineFile = getBaselineFile();
            }
        }
    };

    /**
     * Any user of this should be within session sync
     * @param ctx
     * @return
     */
    UserLocaleStuff getOldUserFile(CookieSession session, CLDRLocale locale) {
        UserLocaleStuff uf = (UserLocaleStuff)session.getByLocale(USER_FILE_KEY, locale.toString());
        return uf;
    }

    //private CLDRFileCache cldrFileCache = null; // LOCAL to UserFile.
    
    public synchronized CLDRFileCache getCLDRFileCache() {
//        if(cldrFileCache == null) {
//        
//            Connection conn = getDBConnection();
//            XMLSource dbSource = makeDBSource(conn, null, CLDRLocale.ROOT, false);
//            XMLSource dbSourceV = makeDBSource(conn, null, CLDRLocale.ROOT, true);
//            cldrFileCache = new CLDRFileCache(dbSource, dbSourceV, new File(homeFile, "vxpt"), this);
//        }
//        return null;
        throw new InternalError("getCLDRFileCache: not imp");
//        return dbsrcfac.getCLDRFileCache();
    }

    /**
     * Return the UserLocaleStuff for the current context.
     * Any user of this should be within session sync (ctx.session) and must be balanced with calls to close();
     * @param ctx
     * @param user
     * @param locale
     * @see UserLocaleStuff#close()
     * @see WebContext#getUserFile()
     */
    public UserLocaleStuff getUserFile(CookieSession session,  CLDRLocale locale) {
        // has this locale been invalidated?
        //UserLocaleStuff uf = null;
        UserLocaleStuff uf = null; //getOldUserFile(session, locale);
//        if(uf!=null && !uf.isValid()) {
//        	uf.close();
//        	uf = null;
//        }
//        if(uf == null) {
            uf = new UserLocaleStuff(locale);
//            session.putByLocale(USER_FILE_KEY, locale.toString(),uf);
//            uf.register(); // register with lcr
//        }
        uf.open(); // incr count.
        
        int n = getDBSourceFactory().update();
        if(n>0) SurveyLog.logger.warning("getUserFile() updated " + n + " locales.");
        return uf;
    }
    XMLSource makeDBSource(CLDRLocale locale) {
        return makeDBSource(locale,true,false);
//        return getSTFactory().makeSource(locale.getBaseName());
//        XMLSource dbSource = getDBSourceFactory().getInstance(locale);
//        return dbSource;
    }
    XMLSource makeDBSource(CLDRLocale locale, boolean finalData) {
        return makeDBSource(locale);
//        XMLSource dbSource = getDBSourceFactory().getInstance(locale, finalData);
//        return dbSource;
    }
    XMLSource makeDBSource(CLDRLocale locale, boolean finalData, boolean resolved) {
        return getSTFactory().makeSource(locale.getBaseName(),resolved);
//        // HACK: CLDRDBSourceFactory has a "final data" source version so we have
//        // to create the XMLSources for resolution directly here. The factory
//        // should really be split into two factories.
//        if (resolved) {
//            List<XMLSource> sources = new ArrayList<XMLSource>();
//            CLDRLocale curLocale = locale;
//            while(curLocale != null) {
//                sources.add(getDBSourceFactory().getInstance(curLocale, finalData));
//                curLocale = curLocale.getParent();
//            }
//            return Factory.makeResolvingSource(sources);
//        } else {
//            return getDBSourceFactory().getInstance(locale, finalData);
//        }
    }
    static CLDRFile makeCLDRFile(XMLSource dbSource) {
        return new CLDRFile(dbSource).setSupplementalDirectory(supplementalDataDir);
    }

    /**
     * reset the "list of locales".  
     * call this if you're resetting the in-db view of what's on disk.  
     * it will reset things like the locale list map, the metazone map, the interest map, ...
     */
    private synchronized void resetLocaleCaches() {
        localeTree = null;
        localeListSet = null;
        aliasMap = null;
        gBaselineFile=null;
        gBaselineHash=null;
        try {
        	Connection conn = null;
        	try {
        		conn = dbUtils.getDBConnection();
        		this.fora.reloadLocales(conn);
        	} finally {
        		DBUtils.close(conn);
        	}
        } catch(SQLException se) {
        	SurveyLog.logger.warning("On resetLocaleCaches().reloadLocales: " + DBUtils.unchainSqlException(se));
        	this.busted("trying to reset locale caches @ fora", se);
        }
    }
    
    
    private static Hashtable<CLDRLocale,CLDRLocale> aliasMap = null;
    private static Hashtable<CLDRLocale,String> directionMap = null;
    
    /**
     * "Hash" a file to a string, including mod time and size
     * @param f
     * @return
     */
    private static String fileHash(File f) {
        return("["+f.getAbsolutePath()+"|"+f.length()+"|"+f.hashCode()+"|"+f.lastModified()+"]");
    }

    private synchronized void checkAllLocales() {
        if(aliasMap!=null) return;

        boolean useCache = isUnofficial; // NB: do NOT use the cache if we are in unofficial mode.  Parsing here doesn't take very long (about 16s), but 
        // we want to save some time during development iterations.

        Hashtable<CLDRLocale,CLDRLocale> aliasMapNew = new Hashtable<CLDRLocale,CLDRLocale>();
        Hashtable<CLDRLocale,String> directionMapNew = new Hashtable<CLDRLocale,String>();
        Set<CLDRLocale> locales  = getLocalesSet();
        ElapsedTimer et = new ElapsedTimer();
        CLDRProgressTask progress = openProgress("Parse locales from XML", locales.size());
        try {
            File xmlCache = new File(vetdir, XML_CACHE_PROPERTIES);
            File xmlCacheBack = new File(vetdir, XML_CACHE_PROPERTIES+".backup");
            Properties xmlCacheProps = new java.util.Properties(); 
            Properties xmlCachePropsNew = new java.util.Properties(); 
            if(useCache && xmlCache.exists()) try {
                java.io.FileInputStream is = new java.io.FileInputStream(xmlCache);
                xmlCacheProps.load(is);
                is.close();
            } catch(java.io.IOException ioe) {
                /*throw new UnavailableException*/
                SurveyLog.logger.log(java.util.logging.Level.SEVERE, "Couldn't load XML Cache file from '" + cldrHome + "/" + XML_CACHE_PROPERTIES + ": ",ioe);
                busted("Couldn't load XML Cache file from '" + cldrHome + "/" + XML_CACHE_PROPERTIES + ": ", ioe);
                return;
            }

            int n=0;
            int cachehit=0;
            SurveyLog.logger.warning("Parse " + locales.size() + " locales from XML to look for aliases or errors...");
            for(File f : getInFiles()) {
            	CLDRLocale loc = fileNameToLocale(f.getName());
                String locString = loc.toString();
                //            ULocale uloc = new ULocale(locString);
                progress.update(n++, loc.toString() /* + " - " + uloc.getDisplayName(uloc) */);
                try {
                    //                String fileName = fileBase+"/"+loc.toString()+".xml";
                    String fileHash = fileHash(f);
                    String aliasTo = null;
                    String direction = null;
                    //SurveyLog.logger.warning(fileHash);

                    String oldHash = xmlCacheProps.getProperty(locString);
                    if(useCache && oldHash != null && oldHash.equals(fileHash)) {
                        // cache hit! load from cache
                        aliasTo = xmlCacheProps.getProperty(locString+".a",null);
                        direction = xmlCacheProps.getProperty(locString+".d",null);
                        cachehit++;
                    } else {
                        Document d = LDMLUtilities.parse(f.getAbsolutePath(), false);

                        // look for directionality
                        Node[] directionalityItems = 
                            LDMLUtilities.getNodeListAsArray(d,"//ldml/layout/orientation");
                        if(directionalityItems!=null&&directionalityItems.length>0) {
                            direction = LDMLUtilities.getAttributeValue(directionalityItems[0], LDMLConstants.CHARACTERS);
                            if(direction != null&& direction.length()>0) {
                            } else {
                                direction = null;
                            }
                        }


                        Node[] aliasItems = 
                            LDMLUtilities.getNodeListAsArray(d,"//ldml/alias");

                        if((aliasItems==null) || (aliasItems.length==0)) {
                            aliasTo=null;
                        } else if(aliasItems.length>1) {
                            throw new InternalError("found " + aliasItems.length + " items at " + "//ldml/alias" + " - should have only found 1");
                        } else {
                            aliasTo = LDMLUtilities.getAttributeValue(aliasItems[0],"source");
                        }
                    }

                    // now, set it into the new map
                    xmlCachePropsNew.put(locString, fileHash);
                    if(direction != null) {
                        directionMapNew.put((loc), direction);
                        xmlCachePropsNew.put(locString+".d", direction);
                    }
                    if(aliasTo!=null) {
                        aliasMapNew.put((loc),CLDRLocale.getInstance(aliasTo));
                        xmlCachePropsNew.put(locString+".a", aliasTo);
                    }
                } catch (Throwable t) {
                    SurveyLog.logger.warning("isLocaleAliased: Failed load/validate on: " + loc + " - " + t.toString());
                    t.printStackTrace();
                    busted("isLocaleAliased: Failed load/validate on: " + loc + " - ", t);
                    throw new InternalError("isLocaleAliased: Failed load/validate on: " + loc + " - " + t.toString());
                }
            }

            if(useCache) try {
                // delete old stuff
                if(xmlCacheBack.exists()) { 
                    xmlCacheBack.delete();
                }
                if(xmlCache.exists()) {
                    xmlCache.renameTo(xmlCacheBack);
                }
                java.io.FileOutputStream os = new java.io.FileOutputStream(xmlCache);
                xmlCachePropsNew.store(os, "YOU MAY DELETE THIS CACHE. Cache updated at " + new Date());
                progress.update(n++, "Loading configuration..");
                os.close();
            } catch(java.io.IOException ioe) {
                /*throw new UnavailableException*/
                SurveyLog.logger.log(java.util.logging.Level.SEVERE, "Couldn't write "+xmlCache+" file from '" + cldrHome + "': ",ioe);
                busted("Couldn't write "+xmlCache+" file from '" + cldrHome+"': ", ioe);
                return;
            }

            SurveyLog.logger.warning("Finished verify+alias check of " + locales.size()+ ", " + aliasMapNew.size() + " aliased locales ("+cachehit+" in cache) found in " + et.toString());
            aliasMap = aliasMapNew;
            directionMap = directionMapNew;
        } finally {
            progress.close();
        }
    }
    
    /**
     * Is this locale fully aliased? If true, returns what it is aliased to.
     */
    public synchronized CLDRLocale isLocaleAliased(CLDRLocale id) {
        if(aliasMap==null) {
            checkAllLocales();
        }
        return aliasMap.get(id);
    }

//    public ULocale isLocaleAliased(ULocale id) {
//        String aliasTo = isLocaleAliased(id.getBaseName()).toString();
//        if(aliasTo!=null) {
//            return new ULocale(aliasTo);
//        } else {
//            return null;
//        }
//    }

    /**
     * Maintain a master list of metazones, culled from root.
     */
    public final Set<String> getMetazones() {
	return supplementalDataInfo.getAllMetazones();
    }

    public Set<String> getMetazones(String subclass) {
	Set<String> subSet = new TreeSet<String>();
	for(String zone : supplementalDataInfo.getAllMetazones()) {
	    if(subclass.equals(supplementalDataInfo.getMetazoneToContinentMap().get(zone))) {
		    subSet.add(zone);
	    }
	}
	return subSet;
    }

    public String getMetazoneContinent(String xpath) {
       XPathParts parts = new XPathParts(null, null);
       SupplementalDataInfo mySupp = getSupplementalDataInfo();
       parts.set(xpath);
       String thisMetazone = parts.getAttributeValue(3,"type");
       return mySupp.getMetazoneToContinentMap().get(thisMetazone);
    }

    /**
    * show the webpage for one of the 'locale codes' items.. 
     * @param ctx the web context
     * @param which menu item to use
     */
    public void showLocaleCodeList(WebContext ctx, String which) {
        showPathList(ctx, PathUtilities.LOCALEDISPLAYNAMES+which, typeToSubtype(which), true /* simple */);
    }

    public void showPathListExample(WebContext ctx, String xpath, String lastElement,
            String e, String fullThing, CLDRFile cf) {
        DataSection oldSection =  ctx.getExistingSection(fullThing);
        DataSection.ExampleEntry ee = null;
        if(oldSection != null) {
            ee = oldSection.getExampleEntry(e); // retrieve the info out of the hash.. 
        }
        if(ee != null) synchronized (ctx.session) {
            ctx.println("<form method=POST action='" + ctx.base() + "'>");
            ctx.printUrlAsHiddenFields();   
            String cls = shortClassName(ee.status.getCause());
            ctx.printHelpLink("/"+cls+"-example","Help with this "+cls+" example", true);
            ctx.println("<hr>");
            ctx.addQuery(QUERY_EXAMPLE,e);
            ctx.println("<input type='hidden' name='"+QUERY_EXAMPLE+"' value='"+e+"'>");
            
            // keep the Demo with the user. for now.
            CheckCLDR.SimpleDemo d = (CheckCLDR.SimpleDemo)ctx.getByLocale(e);
            if(d==null) {        
                d = ee.status.getDemo();
                ctx.putByLocale(e,d);
            }
                
            Map mapOfArrays = ctx.getParameterMap();
            Map<String,String> m = new TreeMap<String,String>();
            for(Iterator i = mapOfArrays.keySet().iterator();i.hasNext();) {
                String k = i.next().toString();
    //            String[] v = (String[])mapOfArrays.get(k);  // We dont care about the broken, undecoded contents here..
                m.put(k,ctx.field(k,null)); //  .. use our vastly improved field() function
            }
            
            if(d != null) {
            
                try {
                    String path = ee.dataRow.getXpath();
                    String fullPath = cf.getFullXPath(path);
                    String value = ee.item.value;
                    String html = d.getHTML(m);
                    ctx.println(html);
                } catch (Exception ex) {
                    ctx.println("<br><b>Error: </b> " + ex.toString() +"<br>");
                }
            }
            ctx.println("</form>");
        } else {
            ctx.println("<P><P><P><blockquote><i>That example seems to have expired. Perhaps the underlying data has changed? Try reloading the parent page, and clicking the Example link again.</i></blockquote>");
        }
    }

    /**
     * @deprecated  -better to use SupplementalDataInfo if possible
     */
    public SupplementalData supplemental = null;

    SupplementalDataInfo supplementalDataInfo = null;

    public final SupplementalDataInfo getSupplementalDataInfo() { 
	//if(supplementalDataInfo==null ) {
	//	    supplementalDataInfo = SupplementalDataInfo.getInstance(getFactory().getSupplementalDirectory());
	//	}
	return supplementalDataInfo;
    }

    public void showMetazones(WebContext ctx, String continent) {
        showPathList(ctx, "//ldml/dates/timeZoneNames/metazone"+DataSection.CONTINENT_DIVIDER+continent,null);
    }

    /**
     * parse the metazone list for this zone.
     * Map will contain enries with key String and value  String[3] of the form:
     *    from : { from, to, metazone }
     * for example:
     *    "1970-01-01" :  { "1970-01-01", "1985-03-08", "Australia_Central" }
     * the 'to' will be null if it does not have an ending time.
     * @param metaMap  an 'out' parameter which will be cleared, and populated with the contents of the metazone
     * @return the active metazone ( where to=null ) if any, or null
     */
    public String zoneToMetaZone(WebContext ctx, String zone, Map metaMap) {
        SurveyMain sm = this;
       // String returnZone = null;
        String current = null;
        XPathParts parts = new XPathParts(null, null);
        synchronized(ctx.session) { // TODO: redundant sync?
            SurveyMain.UserLocaleStuff uf = ctx.getUserFile();
            //CLDRFile cf = uf.cldrfile;
            CLDRFile resolvedFile = uf.resolvedFile;
            //CLDRFile engFile = ctx.sm.getBaselineFile();
    
            String xpath =  "//ldml/"+"dates/timeZoneNames/zone";
            String ourSuffix = "[@type=\""+zone+"\"]";
            //String base_xpath = xpath+ourSuffix;
            String podBase = DataSection.xpathToSectionBase(xpath);
            
            metaMap.clear();
    
            Iterator mzit = resolvedFile.iterator(podBase+ourSuffix+"/usesMetazone");
                 
            for(;mzit.hasNext();) {
                String ameta = (String)mzit.next();
                String mfullPath = resolvedFile.getFullXPath(ameta);
                parts.set(mfullPath);
                String mzone = parts.getAttributeValue(-1,"mzone");
                String from = parts.getAttributeValue(-1,"from");
                if(from==null) {
                    from = METAZONE_EPOCH;
                }
                String to = parts.getAttributeValue(-1,"to");
                String contents[] = { from, to, mzone };
                metaMap.put(from,contents);
                
                if(to==null) {
                    current = mzone;
                }
            }
        }
        return current;
    }


    /**
     * for showing the list of zones to the user
     */

    public void showTimeZones(WebContext ctx) {
        String zone = ctx.field(QUERY_ZONE);

//  Removed this since zoneFormatting data ( and thus OlsonVersion ) no longer exists in CLDR
//        try {
//            ctx.println("<div style='float: right'>TZ Version:"+supplemental.getOlsonVersion()+"</div>");
//        } catch(Throwable t) {
//            ctx.println("<div style='float: right' class='warning'>TZ Version: "+ t.toString()+"</div>");
//        }

        // simple case - show the list of zones.
        if((zone == null)||(zone.length()==0)) {
            showPathList(ctx, DataSection.EXEMPLAR_PARENT, XPathMatcher.regex(Pattern.compile(".*exemplarCity.*")), null);
            return;
        }

        boolean canModify = ctx.getCanModify();
        
        WebContext subCtx = (WebContext)ctx.clone();
        subCtx.setQuery(QUERY_ZONE, zone);
        
        Map metaMap = new TreeMap();
        
        String currentMetaZone = zoneToMetaZone(ctx, zone, metaMap);

        String territory = getSupplementalDataInfo().getZone_territory(zone);
        String displayTerritory = null;
        String displayZone = null;
        if((territory != null) && (territory.length()>0)) {
            displayTerritory = new ULocale("und_"+territory).getDisplayCountry(BASELINE_LOCALE);
            if((displayTerritory == null)||(displayTerritory.length()==0)) {
                displayTerritory = territory;
            }
            displayZone = displayTerritory + " : <a class='selected'>"+ zone + "</a>";
        } else {
            displayZone = zone;
        }

        ctx.println("<h2>"+ displayZone + "</h2>"); // couldn't find the territory.


        printPathListOpen(ctx);
        
        
        ctx.println("<input type='hidden' name='_' value='"+ctx.getLocale().toString()+"'>");
        ctx.println("<input type='hidden' name='x' value='timezones'>");
        ctx.println("<input type='hidden' name='zone' value='"+zone+"'>");
        if(canModify) { 
            ctx.println("<input  type='submit' value='" + getSaveButtonText() + "'>"); // style='float:right'
        }
        
        String zonePodXpath =  "//ldml/"+"dates/timeZoneNames/zone";
        String zoneSuffix = "[@type=\""+zone+"\"]";
        String zoneXpath = zonePodXpath+zoneSuffix;
        String podBase = DataSection.xpathToSectionBase(zonePodXpath);

        
        String metazonePodXpath =  "//ldml/"+"dates/timeZoneNames/metazone";
        String metazoneSuffix = "[@type=\""+currentMetaZone+"\"]";
        String metazoneXpath = metazonePodXpath+metazoneSuffix;
        String metazonePodBase = DataSection.xpathToSectionBase(metazonePodXpath);

        if(canModify) {
            vet.processPodChanges(ctx, podBase);
            if(currentMetaZone != null) {
                vet.processPodChanges(ctx, metazonePodBase);
            }
        }
        
        DataSection section = ctx.getSection(podBase);
        DataSection metazoneSection = null;
        
        if(currentMetaZone != null) {
            metazoneSection = ctx.getSection(metazonePodBase);
        }

        
        // #1 exemplar city
        
        ctx.println("<h3>Exemplar City</h3>");
        
        printSectionTableOpen(ctx, section, true, canModify);
        section.showSection(ctx, canModify, zoneXpath+"/exemplarCity", true);
        printSectionTableClose(ctx, section, canModify);
        
        ctx.printHelpHtml(zoneXpath+"/exemplarCity");

        if(currentMetaZone != null) {
            // #2 there's a MZ active. Explain it.
            ctx.println("<hr><h3>Metazone "+currentMetaZone+"</h3>");

            printSectionTableOpen(ctx, metazoneSection, true, canModify);
            metazoneSection.showSection(ctx, canModify, metazoneXpath, true);
            printSectionTableClose(ctx, metazoneSection, canModify);
            if(canModify) {
                ctx.println("<input  type='submit' value='" + getSaveButtonText() + "'>"); // style='float:right'
            }
            
            ctx.printHelpHtml(metazoneXpath);

            // show the table of active zones
            ctx.println("<h4>Metazone History</h4>");
            ctx.println("<table class='tzbox'>");
            ctx.println("<tr><th>from</th><th>to</th><th>Metazone</th></tr>");
            int n = 0;
            for(Iterator it = metaMap.entrySet().iterator();it.hasNext();) {
                n++;
                Map.Entry e = (Map.Entry)it.next();
                String contents[] = (String[])e.getValue();
                String from = contents[0];
                String to = contents[1];
                String mzone = contents[2];
                // OK, now that we are unpacked..
                //mzContext.setQuery("mzone",mzone);
                String mzClass="";
                if(mzone.equals(currentMetaZone)) {
                    mzClass="currentZone";
                }
                mzClass = "r"+(n%2)+mzClass; //   r0 r1 r0 r1 r1currentZone    ( or r0currentZone )
                ctx.println("<tr class='"+mzClass+"'><td>");
                if(from != null) {
                    ctx.println(from);
                }
                ctx.println("</td><td>");
                if(to != null) {
                    ctx.println(to);
                } else {
                    ctx.println("<i>now</i>");
                }
                ctx.println("</td><td>");
                ctx.println("<tt class='codebox'>");
                
                ctx.print("<span class='"+(mzone.equals(currentMetaZone)?"selected":"notselected")+"' '>"); // href='"+mzContext.url()+"
                ctx.print(mzone);
                ctx.println("</a>");
                ctx.println("</td></tr>");
                
            }
            ctx.println("</table>");

            ctx.println("<h3>"+ displayZone + " Overrides</h3>"); // couldn't find the territory.
            ctx.print("<i>The Metazone <b>"+currentMetaZone+"</b> is active for this zone. " +
                    //<a href=\""+
                //bugReplyUrl(BUG_METAZONE_FOLDER, BUG_METAZONE, ctx.getLocale()+":"+ zone + ":" + currentMetaZone + " incorrect")+
                //"\">Click Here to report Metazone problems</a>"+
                " Please report any Metazone problems. </i>");
        } else {
            ctx.println("<h3>Zone Contents</h3>"); // No metazone - this is just the contents.
        }
                
        printSectionTableOpen(ctx, section, true, canModify);
        // use a special matcher.
        section.showSection(ctx, canModify, XPathMatcher.regex(BaseAndPrefixMatcher.getInstance(XPathTable.NO_XPATH,zoneXpath),
        		 Pattern.compile(".*/((short)|(long))/.*")), true);
        printSectionTableClose(ctx, section, canModify);
        if(canModify) {
            ctx.println("<input  type='submit' value='" + getSaveButtonText() + "'>"); // style='float:right'
        }
        
        ctx.printHelpHtml(zoneXpath);
        
        printPathListClose(ctx);
    }
        

    /**
    * This is the main function for showing lists of items (pods).
     */
    public void showPathList(WebContext ctx, String xpath, String lastElement) {
        showPathList(ctx,xpath,lastElement,false);
    }

    /**
     * Holds session lock
     * @param ctx
     * @param xpath the 'base xpath' such as '//ldml/characters'
     * @param lastElement the 'final element' of each item, such as 'exemplarCharacters' 
     * @param simple (ignored)
     */
    public void showPathList(WebContext ctx, String xpath, String lastElement, boolean simple) {
    	showPathList(ctx,xpath,null, lastElement);
    }
    public void showPathList(WebContext ctx, String xpath, XPathMatcher matcher, String lastElement) {
    	/* all simple */
        
       // synchronized(ctx.session) {
//            UserLocaleStuff uf = getUserFile(ctx.session, ctx.getLocale());
//            XMLSource ourSrc = uf.dbSource;
//            CLDRFile cf =  uf.cldrfile;
    	DBEntry entry = null;
    	Thread curThread = Thread.currentThread();
    	String threadName = curThread.getName();
    	if(matcher!=null) {
    		curThread.setName(threadName + ":" + ctx.getLocale() + ":" + matcher.toString());
    	} else if(xpath!=null) {
    		curThread.setName(threadName + ":" + ctx.getLocale() + ":" + xpt.getPrettyPath(xpath));
    	} else {
    		curThread.setName(threadName + ":" + ctx.getLocale() );
    	}
    	try {
    	    CLDRFile cf = getSTFactory().make(ctx.getLocale().getBaseName(), false,DraftStatus.unconfirmed);
    	    BallotBox<User> ballotBox = getSTFactory().ballotBoxForLocale(ctx.getLocale());

	    	//entry = getDBSourceFactory().openEntry(ourSrc);
	    	
            String fullThing = xpath + "/" + lastElement;
        //    boolean isTz = xpath.equals("timeZoneNames");
            if(lastElement == null) {
                fullThing = xpath;
            }    
                
            boolean canModify = ctx.getCanModify();
            
            {
                // TODO: move this into showExample. . .
                String e = ctx.field(QUERY_EXAMPLE);
                if(e.length() > 0) {
                    showPathListExample(ctx, xpath, lastElement, e, fullThing, cf);
                } else {
                    // first, do submissions.
                    if(canModify) {
                        ctx.println("<i id='processPea'>Processing submitted data...</i><br/>");ctx.flush();
                        try {
	                        DataSection oldSection = ctx.getExistingSection(fullThing);
	                        if(processChanges(ctx, oldSection, cf, ballotBox, new DefaultDataSubmissionResultHandler(ctx))) {
//	                            int j = vet.updateResults(oldSection.locale,entry.getConnectionAlias()); // bach 'em
//	                            int d = this.getDBSourceFactory().update(entry.getConnectionAlias()); // then the fac so it can update
//	                            SurveyLog.logger.warning("sm:ppc:dbsrcfac: "+d+" deferred updates done.");
	                            String j = "";
	                            ctx.println("<br> You submitted data or vote changes, <!-- and " + j + " results were updated. As a result, --> your items may show up under the 'priority' or 'proposed' categories.<br>");
	                        }
                        } finally {
                        	ctx.println("<script type=\"text/javascript\">document.getElementById('processPea').innerHTML='';</script>"); ctx.flush();
                        }
                    }
                    ctx.flush(); // give them some status.
            //        SurveyLog.logger.info("Pod's full thing: " + fullThing);
                    DataSection section = ctx.getSection(fullThing); // we load a new pod here - may be invalid by the modifications above.
                    section.showSection(ctx, canModify, matcher, false);
                }
            }
        //}
    	} finally {
        	try {
        		if(entry!=null) entry.close();
        	} catch(SQLException se) {
        		SurveyLog.logger.warning("SQLException when closing dbEntry : " + DBUtils.unchainSqlException(se));
        	}
        	curThread.setName(threadName);
    	}
    }
	static int PODTABLE_WIDTH = 13; /** width, in columns, of the typical data table **/

    static void printSectionTableOpen(WebContext ctx, DataSection section, boolean zoomedIn, boolean canModify) {
        ctx.println("<a name='st_data'></a>");
        ctx.println("<table summary='Data Items for "+ctx.getLocale().toString()+" " + section.xpathPrefix + "' class='data' border='0'>");

        int table_width = section.hasExamples?13:10;
        int itemColSpan;
        if (!canModify) {
        	table_width -= 4; // No vote, change, or no opinion columns
        }
        if (zoomedIn) {
        	table_width += 2;
        	itemColSpan = 2;  // When zoomed in, Proposed and Other takes up 2 columns
        } else {
        	itemColSpan = 1;
        }
        if(/* !zoomedIn */ true) {
            ctx.println("<tr><td colspan='"+table_width+"'>");
            // dataitems_header.jspf
            // some context
            ctx.put(WebContext.DATA_SECTION, section);
            ctx.put(WebContext.ZOOMED_IN, new Boolean(zoomedIn));
            ctx.includeFragment("dataitems_header.jsp");
            ctx.println("</td></tr>");
        }
            ctx.println("<tr class='headingb'>\n"+
                    " <th width='30'>St.</th>\n"+                  // 1
                    " <th width='30'>Draft</th>\n");                  // 1
            if (canModify) {
                ctx.print(" <th width='30'>Voted</th>\n");                  // 1
            }
            ctx.print(" <th>Code</th>\n"+                 // 2
                        " <th title='["+BASELINE_LOCALE+"]'>"+BASELINE_LANGUAGE_NAME+"</th>\n");
            if (section.hasExamples){ 
                ctx.print(" <th title='"+BASELINE_LANGUAGE_NAME+" ["+BASELINE_LOCALE+"] Example'><i>Ex</i></th>\n"); 
            }
            
            ctx.print(" <th colspan="+itemColSpan+">"+getProposedName()+"</th>\n");
            if (section.hasExamples){ 
                ctx.print(" <th title='Proposed Example'><i>Ex</i></th>\n"); 
            }
            ctx.print(" <th colspan="+itemColSpan+">"+CURRENT_NAME+"</th>\n");
            if (section.hasExamples){ 
                ctx.print(" <th title='Current Example'><i>Ex</i></th>\n");
            }
            if (canModify) { 
                ctx.print(" <th colspan='2' width='25%'>Change</th>\n");  // 8
                ctx.print( "<th width='20' title='No Opinion'>n/o</th>\n"); // 5
            }
            ctx.println("</tr>");
 
        if(zoomedIn) {
            List<String> refsList = new ArrayList<String>();
            ctx.temporaryStuff.put("references", refsList);
        }
    }

    /**
     * section may be null.
     * @param ctx
     * @param section
     */
    static void printSectionTableOpenShort(WebContext ctx, DataSection section) {
        ctx.println("<a name='st_data'></a>");
        ctx.print("<table ");
        if(section != null) {
        	ctx.print(" summary='Data Items for "+ctx.getLocale().toString()+" " + section.xpathPrefix + "' ");
        }
        ctx.println("class='data' border='1'>");
            ctx.println("<tr class='headingb'>\n"+
                        " <th colspan='1' width='50%'>["+BASELINE_LOCALE+"] "+BASELINE_LANGUAGE_NAME+"</th>\n"+              // 3
                        " <th colspan='2' width='50%'>Your Language</th>\n");  // 8

        ctx.println("</tr>");
    
    }
    
    /**
     * section may be null.
     * @param ctx
     * @param section
     */
    static void printSectionTableOpenCode(WebContext ctx) {
        ctx.includeFragment("datarow_open_table_code.jsp");
    	ctx.flush();
    }
    

    /**
     * Print closing table
     * @param ctx
     */
    static void printSectionTableCloseCode(WebContext ctx) {
        ctx.includeFragment("datarow_close_table_code.jsp");
    	ctx.flush();
    }

    /**
     * Section may be null.
     * @param ctx
     * @param section
     */
    void printSectionTableClose(WebContext ctx, DataSection section, boolean canModify) {
        int table_width = section.hasExamples?13:10;
        if (!canModify) {
        	table_width -= 4; // No vote, change, or no opinion columns
        }

        List<String> refsList = (List<String>) ctx.temporaryStuff.get("references");
        if(section!=null && (refsList != null) && (!refsList.isEmpty())) {
            ctx.println("<tr></tr>");
            ctx.println("<tr class='heading'><th class='partsection' align='left' colspan='"+table_width+"'>References</th></tr>");
            int n = 0;
            
            Hashtable<String,DataSection.DataRow> refsHash = (Hashtable<String, DataSection.DataRow>)ctx.temporaryStuff.get("refsHash");
            Hashtable<String,DataSection.DataRow.CandidateItem> refsItemHash = (Hashtable<String, DataSection.DataRow.CandidateItem>)ctx.temporaryStuff.get("refsItemHash");
            
            for(String ref: refsList) {
                n++;
                ctx.println("<tr class='referenceRow'><th><img src='http://unicode.org/cldr/data/dropbox/misc/images/reference.jpg'>#"+n+"</th>");
                ctx.println("<td colspan='"+1+"'>"+ref+"</td>");
                ctx.print("<td colspan='"+(table_width-2)+"'>");
                if(refsHash != null) {
                    DataSection.DataRow refDataRow = refsHash.get(ref);
                    DataSection.DataRow.CandidateItem refDataRowItem = refsItemHash.get(ref);
                    if((refDataRowItem != null)&&(refDataRow!=null)) {
                        ctx.print(refDataRowItem.value);
                        if(refDataRow.getDisplayName() != null) {
                            ctx.println("<br>"+refDataRow.getDisplayName());
                        }
                        //ctx.print(refPea.displayName);
                    } else {
                        ctx.print("<i>unknown reference</i>");
                    }
                }
                ctx.print("</td>");
                ctx.println("</tr>");
            }
            
        }
        if(refsList != null) {
            ctx.temporaryStuff.remove("references");
            ctx.temporaryStuff.remove("refsHash");
            ctx.temporaryStuff.remove("refsItemHash");
        }
        
        ctx.println("</table>");
    }

	/**
     * Call from within session lock
     * @param ctx
     * @param oldSection
     * @param cf
     * @param ballotBox
     * @param dsrh 
     * @return
     */
    boolean processChanges(WebContext ctx, DataSection oldSection, CLDRFile cf, BallotBox<User> ballotBox, DataSubmissionResultHandler dsrh) {
        boolean someDidChange = false;
        if(oldSection != null) {
            for(Iterator i = oldSection.getAll().iterator();i.hasNext();) {
                DataSection.DataRow p = (DataSection.DataRow)i.next();
                someDidChange = p.processDataRowChanges(ctx, this, cf, ballotBox, dsrh) || someDidChange;
//                if(p.subRows != null) {
//                    for(Iterator e = p.subRows.values().iterator();e.hasNext();) {
//                        DataSection.DataRow subDataRow = (DataSection.DataRow)e.next();
//                        someDidChange = processDataRowChanges(ctx, oldSection, subDataRow, cf, ballotBox, dsrh) || someDidChange;
//                    }
//                }
            }            
        }
        if(someDidChange) {
        	SurveyLog.logger.warning("SomeDidChange: " + oldSection.locale());
    		int updcount = getDBSourceFactory().update();
    		int updcount2 = getDBSourceFactory().sm.vet.updateResults(oldSection.locale());
    		SurveyLog.logger.warning("Results updated: " + updcount + ", " + updcount2 + " for " + oldSection.locale());
            updateLocale(oldSection.locale());
        }
        return someDidChange;
    }

    public void updateLocale(CLDRLocale locale) {
        lcr.invalidateLocale(locale);
        int n = vet.updateImpliedVotes(locale); // first implied votes
        SurveyLog.logger.warning("updateLocale:"+locale.toString()+":  vet_imp:"+n);
    }
    boolean doVote(WebContext ctx, CLDRLocale locale, int xpath) {
        int base_xpath = xpt.xpathToBaseXpathId(xpath);
        return doVote(ctx, locale, xpath, base_xpath);
    }

    boolean doVote(WebContext ctx, CLDRLocale locale, int xpath, int base_xpath) {
        return doVote(ctx, locale, xpath, base_xpath, ctx.session.user.id);
    }

    boolean doVote(WebContext ctx, CLDRLocale locale, int xpath, int base_xpath, int id) {
        vet.vote( locale,  base_xpath, id, xpath, Vetting.VET_EXPLICIT);
      //  lcr.invalidateLocale(locale); // throw out this pod next time, cause '.votes' are now wrong.
        return true;
    }

    boolean doAdminRemoveVote(WebContext ctx, CLDRLocale locale, int base_xpath, int id) {
        vet.vote( locale,  base_xpath, id, -1, Vetting.VET_ADMIN);
     //   lcr.invalidateLocale(locale); // throw out this pod next time, cause '.votes' are now wrong.
        return true;
    }

    int doUnVote(WebContext ctx, CLDRLocale locale, int base_xpath) {
        return doUnVote(ctx, locale, base_xpath, ctx.session.user.id);
    }
    
    int doUnVote(WebContext ctx, CLDRLocale locale, int base_xpath, int submitter) {
        int rs = vet.unvote( locale,  base_xpath, submitter);
      //  lcr.invalidateLocale(locale); // throw out this pod next time, cause '.votes' are now wrong.
        return rs;
    }

static final UnicodeSet CallOut = new UnicodeSet("[\\u200b-\\u200f]");


    
    public static int pages=0;
    public static int xpages=0;
    /**
    * Main setup
     */
    static public boolean isSetup = false;

    private void createBasicCldr(File homeFile) {
        SurveyLog.logger.warning("Attempting to create /cldr  dir at " + homeFile.getAbsolutePath());

        try {
            homeFile.mkdir();
            File propsFile = new File(homeFile, "cldr.properties");
            OutputStream file = new FileOutputStream(propsFile, false); // Append
            PrintWriter pw = new PrintWriter(file);

            pw.println("## autogenerated cldr.properties config file");
            pw.println("## generated on " + localhost() + " at "+new Date());
            pw.println("## see the readme at \n## "+URL_CLDR+"data/tools/java/org/unicode/cldr/web/data/readme.txt ");
            pw.println("## make sure these settings are OK,\n## and comment out CLDR_MESSAGE for normal operation");
            pw.println("##");
            pw.println("## SurveyTool must be reloaded, or the web server restarted, \n## for these to take effect.");
            pw.println();
            pw.println("## your password. Login as user 'admin@' and this password for admin access.");
            pw.println("CLDR_VAP="+UserRegistry.makePassword("admin@"));
            pw.println();
            pw.println("## Special message shown to users as to why survey tool is down.");
            pw.println("## Comment out for normal start-up.");
            pw.println("CLDR_MESSAGE=Welcome to SurveyTool@"+localhost()+". Please edit "+propsFile.getAbsolutePath()+". Comment out CLDR_MESSAGE to continue normal startup.");
            pw.println();
            pw.println("## Special message shown to users.");
            pw.println("CLDR_HEADER=Welcome to SurveyTool@"+localhost()+". Please edit "+propsFile.getAbsolutePath()+" to change CLDR_HEADER (to change this message), or comment it out entirely.");
            pw.println();
            pw.println("## Current SurveyTool phase ");
            pw.println("CLDR_PHASE="+Phase.BETA.name());
            pw.println();
            pw.println("## 'old' (previous) version");
            pw.println("CLDR_OLDVERSION=CLDR_OLDVERSION");
            pw.println();
            pw.println("## 'new'  version");
            pw.println("CLDR_NEWVERSION=CLDR_NEWVERSION");
            pw.println();
            pw.println("## Current SurveyTool phase ");
            pw.println("CLDR_PHASE="+Phase.BETA.name());
            pw.println();
            pw.println("## CLDR common data. Default value shown, uncomment to override");
            pw.println("CLDR_COMMON="+homeFile.getAbsolutePath()+"/common");
            pw.println();
            pw.println("## CLDR seed data. Default value shown, uncomment to override");
            pw.println("CLDR_SEED="+homeFile.getAbsolutePath()+"/seed");
            pw.println();
            pw.println("## SMTP server. Mail is disabled by default.");
            pw.println("#CLDR_SMTP=127.0.0.1");
            pw.println();
            pw.println("## FROM address for mail. Don't be a bad administrator, change this.");
            pw.println("#CLDR_FROM=bad_administrator@"+localhost());
            pw.println();
            pw.println("# That's all!");
            pw.close();
            file.close();
        }
        catch(IOException exception){
          SurveyLog.logger.warning("While writing "+homeFile.getAbsolutePath()+" props: "+exception);
          exception.printStackTrace();
        }
    }
    
    Timer surveyTimer = null;
//    private List<PeriodicTask> periodicTasks = null;
    
    private synchronized Timer getTimer() {
    	if(surveyTimer==null) {
    		surveyTimer=new Timer("SurveyTool Periodic Tasks",true);
    	}
    	return surveyTimer;
    }

//	public interface PeriodicTask {
//		public String name();
//		public void run() throws Throwable;
//	}

    public void addPeriodicTask(TimerTask task) {
		int firstTime=isUnofficial?10000:99000;		
		int eachTime= isUnofficial?10000:76000;
		getTimer().schedule(task, firstTime,eachTime);
//    	if(periodicTasks==null) {
//    		
//    		periodicTasks = new LinkedList<PeriodicTask>();
//    		
//    		// spin up the periodic thread
//    		getTimer().schedule(new TimerTask(){
//
//				@Override
//				public void run() {
//			    	synchronized(periodicTasks) {
//			    		Set<PeriodicTask> bads = new HashSet<PeriodicTask>();
//				    	for(PeriodicTask st : periodicTasks) {
//				    		try {
//								st.run();
//							} catch (Throwable e) {
//								// TODO Auto-generated catch block
//								e.printStackTrace();
//								SurveyLog.logger.warning("Removing periodic task due to crash: " + st.name());
//								bads.add(st);
//							}
//				    	}
//				    	for(PeriodicTask st : bads ) {
//				    		periodicTasks.remove(st);
//				    	}
//			    	}
//			}}, firstTime, eachTime);
//    	}
//    	synchronized(periodicTasks) {
//    		periodicTasks.add(task);
//    	}
    }
    
    /**
     * Class to startup ST in background and perform background operations.
     */
    public transient SurveyThread startupThread = new SurveyThread(this);

    /**
     * Progress bar manager
     */
    SurveyProgressManager progressManager = new SurveyProgressManager();

    static File supplementalDataDir;


    /**
     * Startup function. Called from another thread.
     * @throws ServletException
     */
    public synchronized void doStartup()  {
        if(isSetup == true) {
            return;
        }
        
        ElapsedTimer setupTime = new ElapsedTimer();
        CLDRProgressTask progress = openProgress("Main Startup");
        try {
            // set up CheckCLDR
            //CheckCLDR.SHOW_TIMES=true;
    
            progress.update("Initializing Properties");
            
            survprops = new java.util.Properties(); 
    
            if(cldrHome == null) {
            	getHome();
                if(cldrHome == null) {  
                    return;
                } 
                homeFile = new File(cldrHome, "cldr");
                File propFile = new java.io.File(homeFile, "cldr.properties");
    
                if(!propFile.exists()) {
                    SurveyLog.logger.warning("Does not exist: "+propFile.getAbsolutePath());
                    createBasicCldr(homeFile); // attempt to create
                }
    
                if(!homeFile.exists()) {
                    busted("$(catalina.home)/cldr isn't working as a CLDR home. Not a directory: " + homeFile.getAbsolutePath());
                    return;
                }
                cldrHome = homeFile.getAbsolutePath();
            }
    
            SurveyLog.logger.info("SurveyTool starting up. root=" + new File(cldrHome).getAbsolutePath());
            progress.update("Loading configurations");
    
            try {
                java.io.FileInputStream is = new java.io.FileInputStream(new java.io.File(cldrHome, "cldr.properties"));
                survprops.load(is);
                progress.update("Loading configuration..");
                is.close();
            } catch(java.io.IOException ioe) {
                /*throw new UnavailableException*/
                SurveyLog.logger.log(java.util.logging.Level.SEVERE, "Couldn't load cldr.properties file from '" + cldrHome + "/cldr.properties': ",ioe);
                busted("Couldn't load cldr.properties file from '" + cldrHome + "/cldr.properties': ", ioe);
                return;
            }
            
            progress.update("Setup DB config");
            // set up DB properties
            dbUtils.setupDBProperties(this, survprops);
            progress.update("Setup phase..");
            
            // phase
            {
                String phaseString = survprops.getProperty("CLDR_PHASE",null);
                try {
                    if(phaseString!=null) {
                        currentPhase = (Phase.valueOf(phaseString));
                    }
                } catch(IllegalArgumentException iae) {
                    SurveyLog.logger.warning("Error trying to parse CLDR_PHASE: " + iae.toString());
                }
                if(currentPhase == null) {
                    StringBuffer allValues = new StringBuffer();
                    for (Phase v : Phase.values()) {
                         allValues.append(v.name());
                         allValues.append(' ');
                    }
                    busted("Could not parse CLDR_PHASE - should be one of ( "+allValues+") but instead got "+phaseString);
                }
            }
            progress.update("Setup props..");
            newVersion=survprops.getProperty("CLDR_NEWVERSION","CLDR_NEWVERSION");
            oldVersion=survprops.getProperty("CLDR_OLDVERSION","CLDR_OLDVERSION");
    
            vetdata = survprops.getProperty("CLDR_VET_DATA", cldrHome+"/vetdata"); // dir for vetted data
            progress.update("Setup dirs.."); 
            vetdir = new File(vetdata);
            if(!vetdir.isDirectory()) {
                vetdir.mkdir();
                SurveyLog.logger.warning("## creating empty vetdir: " + vetdir.getAbsolutePath());
            }
            if(!vetdir.isDirectory()) {
                busted("CLDR_VET_DATA isn't a directory: " + vetdata);
                return;
            }
    
            progress.update("Setup vap and message..");
            vap = survprops.getProperty("CLDR_VAP"); // Vet Access Password
            if((vap==null)||(vap.length()==0)) {
                /*throw new UnavailableException*/
                busted("No vetting password set. (CLDR_VAP in cldr.properties)");
                return;
            }
            if("yes".equals(survprops.getProperty("CLDR_OFFICIAL"))) {
                isUnofficial = false;
            }
            vetweb = survprops.getProperty("CLDR_VET_WEB",cldrHome+"/vetdata"); // dir for web data
            cldrLoad = survprops.getProperty("CLDR_LOAD_ALL"); // preload all locales?
            // System.getProperty("CLDR_COMMON") + "/main" is ignored.
            fileBase = survprops.getProperty("CLDR_COMMON",cldrHome+"/common") + "/main"; // not static - may change lager
            fileBaseSeed = survprops.getProperty("CLDR_SEED",cldrHome+"/seed") + "/main"; // not static - may change lager
            setFileBaseOld(survprops.getProperty(getOldVersionParam(),cldrHome+"/"+oldVersion)); // not static - may change lager
            specialMessage = survprops.getProperty("CLDR_MESSAGE"); // not static - may change lager
            specialHeader = survprops.getProperty("CLDR_HEADER"); // not static - may change lager
            
            lockOut = survprops.getProperty("CLDR_LOCKOUT");
            
            if(!new File(fileBase).isDirectory()) {
                busted("CLDR_COMMON isn't a directory: " + fileBase);
                return;
            }
            if(!new File(fileBaseSeed).isDirectory()) {
                busted("CLDR_SEED isn't a directory: " + fileBaseSeed);
                return;
            }
            
            getOldFactory(); // check old version
    
            if(!new File(vetweb).isDirectory()) {
                busted("CLDR_VET_WEB isn't a directory: " + vetweb);
                return;
            }
            progress.update("Setup cache..");
    
            File cacheDir = new File(cldrHome, "cache");
       //     logger.info("Cache Dir: " + cacheDir.getAbsolutePath() + " - creating and emptying..");
            CachingEntityResolver.setCacheDir(cacheDir.getAbsolutePath());
            CachingEntityResolver.createAndEmptyCacheDir();

            progress.update("Setup supplemental..");
            supplementalDataDir = new File(fileBase,"../supplemental/");
            supplemental = new SupplementalData(supplementalDataDir.getCanonicalPath());
            supplementalDataInfo = SupplementalDataInfo.getInstance(supplementalDataDir);
            supplementalDataInfo.setAsDefaultInstance();
            //CldrUtility.DEFAULT_SUPPLEMENTAL_DIRECTORY = supplementalDataDir.getCanonicalPath();
    
            try {
            	supplemental.defaultContentToParent("mt_MT");
            } catch(InternalError ie) {
            	SurveyLog.logger.warning("can't do SupplementalData.defaultContentToParent() - " + ie);
            	ie.printStackTrace();
            	busted("can't do SupplementalData.defaultContentToParent() - " + ie, ie);
            }
	    	progress.update("Checking if startup completed..");
    
          //  int status = 0;
        //    logger.info(" ------------------ " + new Date().toString() + " ---------------");
            if(isBusted != null) {
                return; // couldn't write the log
            }
            if ((specialMessage!=null)&&(specialMessage.length()>0)) {
                SurveyLog.logger.warning("SurveyTool with CLDR_MESSAGE: " + specialMessage);
                busted("message: " + specialMessage);
            }
            /*
             SurveyMain m = new SurveyMain();  ???
             
             if(!m.reg.read()) {
                 busted("Couldn't load user registry [at least put an empty file there]   - exiting");
                 return;
             }
             */
            progress.update("Setup warnings..");
            if(!readWarnings()) {
                // already busted
                return;
            }
            
            progress.update("Setup baseline file..");
            
            // load baseline file
            getBaselineFile();
            
            progress.update("Setup baseline example..");
            
            // and example
            getBaselineExample();

            progress.update("Wake up the database..");
            
            
            doStartupDB(); // will take over progress 50-60
            
            
            progress.update("Making your Survey Tool happy..");
            
            if(!CldrUtility.getProperty("CLDR_NOUPDATE", false)) {
            	addUpdateTasks();
            }
        } catch(Throwable t) {
	        t.printStackTrace();
	        busted("Error on startup: ", t);
        } finally {
            progress.close();
        }
        
        /** 
         * Cause locale alias to be checked.
         */
        if(!isBusted()) {
            isLocaleAliased(CLDRLocale.ROOT);
        }
        
        SurveyLog.logger.info("SurveyTool ready for requests after "+setupTime+". Memory in use: " + usedK());
        isSetup = true;
    }
    
    private File getDataDir(String kind) throws IOException {
    	File dataDir = new File(vetdir, kind);
    	if(!dataDir.exists()) {
    		if(!dataDir.mkdirs()) {
    			throw new IOException("Couldn't create " + dataDir.getAbsolutePath() );
    		}
    	}
    	return dataDir;
    }
    
    File getDataFile(String kind, CLDRLocale loc) throws IOException {
    	return new File(getDataDir(kind),loc.toString()+".xml");
    }
    
    public boolean fileNeedsUpdate(Connection conn, CLDRLocale loc, String kind) throws SQLException, IOException {
    	return fileNeedsUpdate(getLocaleTime(conn,loc),loc,kind);
    }
    public boolean fileNeedsUpdate(Timestamp theDate, CLDRLocale loc, String kind) throws SQLException, IOException {
		File outFile = getDataFile(kind, loc);
		if(!outFile.exists()) return true;
		Timestamp theFile = null;

		long lastMod = outFile.lastModified();
		if(outFile.exists()) {
			theFile = new Timestamp(lastMod);
		}
		if(theDate==null) {
			return false; // no data (?)
		}
		//SurveyLog.logger.warning(loc+" .. exists " + theFile + " vs " + theDate);
		if(theFile!=null && !theFile.before(theDate)) {
			//SurveyLog.logger.warning(" .. OK, up to date.");
			return false;
		}
		if(false) SurveyLog.logger.warning("Out of Date: Must output " + loc + " / " + kind + " - @" + theFile + " vs  SQL " + theDate);
		return true;
    }
    
    public Timestamp getLocaleTime(Connection conn, CLDRLocale loc) throws SQLException {
		Timestamp theDate = null;
		Object[][] o = dbUtils.sqlQueryArrayArrayObj(conn, "select max(modtime) from cldr_result where locale=?", loc);
		if(o!=null&&o.length>0&&o[0]!=null&&o[0].length>0) {
			theDate = (Timestamp)o[0][0];
		}
		File svnFile = getBaseFile(loc);
		if(svnFile.exists()) {
			Timestamp fileTimestamp = new Timestamp(svnFile.lastModified());
			if(theDate==null || fileTimestamp.after(theDate)) {
				theDate = fileTimestamp;
			}
		}
		
		CLDRLocale parLoc = loc.getParent();
		if(parLoc!=null) {
			Timestamp parTimestamp = getLocaleTime(conn,parLoc);
			if(theDate==null || parTimestamp.after(theDate)) {
				theDate = parTimestamp;
			}
		}
		
		return theDate;
	}
    public Timestamp getLocaleTime(CLDRLocale loc) throws SQLException {
    	Connection conn = null;
    	try {
    		conn = dbUtils.getDBConnection();
    		return getLocaleTime(conn,loc);
    	} finally {
    		DBUtils.close(conn);
    	}
	}
	/**
     * Get output file, creating if necessary
     * @param conn
     * @param loc
     * @param kind
     * @return
     * @throws IOException
     * @throws SQLException
     */
    synchronized File getOutputFile(Connection conn, CLDRLocale loc, String kind) throws IOException, SQLException {
    	if(fileNeedsUpdate(conn,loc,kind)) {
    		return writeOutputFile(loc,kind);
    	} else {
    		return getDataFile(kind,loc);
    	}
    }
    /**
     * Get the output file, creating if needed. Uses a temp Connection
     * @param loc
     * @param kind
     * @return
     * @throws IOException
     * @throws SQLException
     */
    File getOutputFile(CLDRLocale loc, String kind) throws IOException, SQLException {
		Connection conn = null;
		try {
			conn = dbUtils.getDBConnection();
			return getOutputFile(conn,loc,kind);
		} finally {
			DBUtils.close(conn);
		}
    }
    
    /**
     * 
     * @param loc
     * @param kind
     * @return
     * @throws IOException
     * @throws SQLException
     */
    boolean fileNeedsUpdate(CLDRLocale loc, String kind) throws IOException, SQLException {
		Connection conn = null;
		try {
			conn = dbUtils.getDBConnection();
			return fileNeedsUpdate(conn,loc,kind);
		} finally {
			DBUtils.close(conn);
		}
    }

    
    /**
     * Write out the specified file. 
     * @param loc
     * @param kind
     * @return
     */
    private File writeOutputFile(CLDRLocale loc, String  kind) {
    	long st = System.currentTimeMillis();
		//ElapsedTimer et = new ElapsedTimer("Output "+loc);
		XMLSource dbSource;
	    CLDRFile file;
	    boolean isFlat = false;
		if(kind.equals("vxml")) {
			dbSource = makeDBSource(loc, true);
		    file = makeCLDRFile(dbSource).setSupplementalDirectory(supplementalDataDir);;
		} else if(kind.equals("fxml")) {
				dbSource = makeDBSource(loc, true);
			    file = makeCLDRFile(dbSource).setSupplementalDirectory(supplementalDataDir);;
			    isFlat=true;
		} else if(kind.equals("rxml")) {
			dbSource = makeDBSource(loc, true, true);
	    	file = new CLDRFile(dbSource).setSupplementalDirectory(supplementalDataDir);
	    } else if(kind.equals("xml")) {
			dbSource = makeDBSource(loc, false);
	    	file = new CLDRFile(dbSource).setSupplementalDirectory(supplementalDataDir);
	    } else {
	    	if(!isCacheableKind(kind)) {
	    		throw new InternalError("Can't (yet) cache kind " + kind + " for loc " + loc);
	    	} else {
	    		throw new InternalError("Don't know how to make kind " + kind + " for loc " + loc + " - isCacheableKind() out of sync with writeOutputFile()");
	    	}
	    }
		DBEntry dbEntry = null;
		try {
			dbEntry = getDBSourceFactory().openEntry(dbSource);
			File outFile = getDataFile(kind, loc);
			PrintWriter u8out = new PrintWriter(
					new OutputStreamWriter(
							new FileOutputStream(outFile), "UTF8"));
			if(!isFlat) {
				file.write(u8out);
			} else {
				Set<String> keys = new TreeSet<String>();
				for(String k : file) {
					keys.add(k);
				}
				u8out.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
				u8out.println("<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">"); 
				u8out.println("<comment>"+loc+"</comment>");
				u8out.println("<properties>");
				for(String k:keys) {
					u8out.println(" <entry key=\""+k.replaceAll("\"", "\\\"")+"\">"+file.getStringValue(k)+"</entry>");
				}
				u8out.println("</properties>");
			}
			u8out.close();
			SurveyLog.logger.warning("Updater: Wrote: " + kind + "/" + loc + " - " +  ElapsedTimer.elapsedTime(st));
			return outFile;
		} catch (IOException e) {
	    	e.printStackTrace();
	    	throw new RuntimeException("IO Exception "+e.toString(),e);
	    } finally {
	    	if(dbEntry!=null) {
				try {
					dbEntry.close();
				} catch (SQLException e) {
					SurveyLog.logger.warning("Error in " + kind + "/" + loc + " _ " + e.toString());
					e.printStackTrace();
				}
	    	}
	    }
    }

    private void addUpdateTasks() {
    	addPeriodicTask(new TimerTask()
    	{
    		int spinner = (int)Math.round(Math.random()*(double)getLocales().length); // Start on a different locale each time.
    		@Override
    		public void run()  {
    			
    			if(SurveyThread.activeCount()>1) {
    				return;
    			}
    			
    			Connection conn = null;
    			CLDRProgressTask progress = null;
    			try {
    				CLDRLocale locs[] = getLocales();
    				File outFile = null;
    				CLDRLocale loc = null;
    				conn = dbUtils.getDBConnection();
    				
    				for(int j=0;j< Math.min(16,locs.length);j++) { // Try 16 locales looking for one that doesn't exist. No more, due to load.
    					loc = locs[(spinner++)%locs.length]; // A new one each time.
    					//SurveyLog.logger.warning("Updater: Considering: "  +loc);
    					Timestamp localeTime = getLocaleTime(conn,loc);
    					if(!fileNeedsUpdate(localeTime,loc,"vxml") /*&& !fileNeedsUpdate(localeTime,loc,"xml")*/ ) {
    						loc=null;
//        					progress.update(0, "Still looking.");
    					}
    				}

    				if(loc==null) {
    					//progress.update(3, "None to update.");
    					//    				SurveyLog.logger.warning("All " + locs.length + " up to date.");
    					return; // nothing to do.
    				}
					progress = openProgress("Updater", 3);
					progress.update(1, "Update vxml:"  +loc);
    				getOutputFile(loc, "vxml");
    				/*
					progress.update(2, "Writing xml:"  +loc);
    				getOutputFile(loc, "xml");
    				*/
					progress.update(3, "Done:"  +loc);
    			} catch (SQLException e) {
					SurveyLog.logger.warning("While running Updater: " + DBUtils.unchainSqlException(e));
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
    				if(progress!=null) progress.close();
					DBUtils.close(conn);
    			}
    		}
    	});
	}
	private String getOldVersionParam() {
        return "CLDR_COMMON"+oldVersion;
    }
    public static boolean isBusted() {
        return (isBusted != null);
    }
    public void destroy() {
        CLDRProgressTask progress = openProgress("shutting down");
        try {
            SurveyLog.logger.warning("SurveyTool shutting down..");
            if(startupThread!=null) {
                progress.update("Attempting clean shutdown...");
            	startupThread.attemptCleanShutdown();
            }
            progress.update("Shutting down database...");
            doShutdownDB();
            progress.update("Destroying timer...");
            if(surveyTimer!=null) {
            	surveyTimer.cancel();
            	surveyTimer=null;
            }
            progress.update("Destroying servlet...");
            if(isBusted!=null) isBusted="servlet destroyed";
            super.destroy();
        } finally {
            progress.close();
        }
    }

    protected void startCell(WebContext ctx, String background) {
        ctx.println("<td bgcolor=\"" + background + "\">");
    }

    protected void endCell(WebContext ctx) {
        ctx.println("</td>");
    }

    protected void doCell(WebContext ctx, String type, String value) {
        startCell(ctx,"#FFFFFF");
        ctx.println(value);
        endCell(ctx);
    }

    protected void doDraftCell(WebContext ctx, String type, String value) {
        startCell(ctx,"#DDDDDD");
        ctx.println("<i>Draft</i><br/>");
        ctx.println(value);
        endCell(ctx);
    }

    protected void doPropCell(WebContext ctx, String type, String value) {
        startCell(ctx,"#DDFFDD");
        ctx.println("<i>Proposed:</i><br/>");
        ctx.println(value);
        endCell(ctx);
    }

    // utils
    private Node getVettedNode(Node context, String resToFetch){
        NodeList list = LDMLUtilities.getChildNodes(context, resToFetch);
        Node node =null;
        if(list!=null){
            for(int i =0; i<list.getLength(); i++){
                node = list.item(i);
                if(LDMLUtilities.isNodeDraft(node)){
                    continue;
                }
                /*
                 if(isAlternate(node)){
                     continue;
                 }
                 */
                return node;
            }
        }
        return null;
    }
    
    static FileFilter getXmlFileFilter() {
    	return new FileFilter() {
            public boolean accept(File f) {
                String n = f.getName();
                return(!f.isDirectory()
                       &&n.endsWith(".xml")
                       &&!n.startsWith(".")
                       &&!n.startsWith("supplementalData") // not a locale
                       /*&&!n.startsWith("root")*/); // root is implied, will be included elsewhere.
            }
        };
    }

    // TODO: seed
    static protected File[] getInFiles() {
    	ElapsedTimer et = SurveyLog.DEBUG?new ElapsedTimer("getInFiles()"):null;
    	Set<File> s = new HashSet<File>();
    	for(File f : getInFiles(fileBase) ) {
    		s.add(f);
    	}
    	for(File f : getInFiles(fileBaseSeed) ) {
    		s.add(f);
    	}
    	File arr[] = s.toArray(new File[s.size()]);
    	SurveyLog.debug(et);
    	return arr;
    }
    
    // TODO: seed
    static protected File[] getInFiles(String base) {
        File baseDir = new File(base);
        return getInFiles(baseDir);
    }
    
    /*
     * Note, do NOT use this with just the base dir, doesn't include seed.
     */
    static protected File[] getInFiles(File baseDir) {
        // 1. get the list of input XML files
        FileFilter myFilter = getXmlFileFilter();
        return baseDir.listFiles(myFilter);
    }
    
    static protected CLDRLocale getLocaleOf(File localeFile) {
		String localeName = localeFile.getName();
		return getLocaleOf(localeName);
    }
    
    static protected CLDRLocale getLocaleOf(String localeName) {
		int dot = localeName.indexOf('.');
		String theLocale = localeName.substring(0,dot);
		return CLDRLocale.getInstance(theLocale);
    }
    
    private static Set<CLDRLocale> localeListSet = null;

    static synchronized public Set<CLDRLocale> getLocalesSet() {
        if(localeListSet == null ) {
            File inFiles[] = getInFiles();
            int nrInFiles = inFiles.length;
            Set<CLDRLocale> s = new HashSet<CLDRLocale>();
            for(int i=0;i<nrInFiles;i++) {
                String fileName = inFiles[i].getName();
                int dot = fileName.indexOf('.');
                if(dot !=  -1) {
                    String locale = fileName.substring(0,dot);
                    s.add(CLDRLocale.getInstance(locale));
                }
            }
            localeListSet = s;
        }
        return localeListSet;
    }

    static public CLDRLocale[] getLocales() {
        return (CLDRLocale[])getLocalesSet().toArray(new CLDRLocale[0]);
    }

    /**
     * Returns a Map of all interest groups.
     * en -> en, en_US, en_MT, ...
     * fr -> fr, fr_BE, fr_FR, ...
     */
    static protected Map<CLDRLocale,Set<CLDRLocale>> getIntGroups() {
        // TODO: rewrite as iterator
        CLDRLocale[] locales = getLocales();
        Map<CLDRLocale,Set<CLDRLocale>> h = new HashMap<CLDRLocale,Set<CLDRLocale>>();
        for(int i=0;i<locales.length;i++) {
            CLDRLocale locale = locales[i];
            CLDRLocale group = locale;
            int dash = locale.toString().indexOf('_');
            if(dash !=  -1) {
                group = CLDRLocale.getInstance(locale.toString().substring(0,dash));
            }
            Set<CLDRLocale> s = h.get(group);
            if(s == null) {
                s = new HashSet<CLDRLocale>();
                h.put(group,s);
            }
            s.add(locale);
        }
        return h;
    }

    public boolean isValidLocale(CLDRLocale locale) {
        return getLocalesSet().contains(locale);
    }

    /* returns a map of String localegroup -> Set [ User interestedUser,  ... ]
    */ 
	protected Map getIntUsers(Map intGroups) {
		Map m = new HashMap();
		Connection conn = null;
		try {
			conn = dbUtils.getDBConnection();
			synchronized (reg) {
				java.sql.ResultSet rs = reg.list(null, conn);
				if (rs == null) {
					return m;
				}
				while (rs.next()) {
					int theirLevel = rs.getInt(2);
					if (theirLevel > UserRegistry.VETTER) {
						continue; // will not receive notice.
					}

					int theirId = rs.getInt(1);
					UserRegistry.User u = reg.getInfo(theirId);
					// String theirName = rs.getString(3);
					// String theirEmail = rs.getString(4);
					// String theirOrg = rs.getString(5);
					String theirLocales = rs.getString(6);
					String theirIntlocs = rs.getString(7);

					String localeArray[] = UserRegistry
							.tokenizeLocale(theirLocales);

					if ((theirId <= UserRegistry.TC)
							|| (localeArray.length == 0)) { // all locales
						localeArray = UserRegistry.tokenizeLocale(theirIntlocs);
					}

					if (localeArray.length == 0) {
						for (Iterator li = intGroups.keySet().iterator(); li
								.hasNext();) {
							String group = (String) li.next();
							Set v = (Set) m.get(group);
							if (v == null) {
								v = new HashSet();
								m.put(group, v);
							}
							v.add(u);
							// SurveyLog.logger.warning(group + " - " + u.email +
							// " (ALL)");
						}
					} else {
						for (int i = 0; i < localeArray.length; i++) {
							String group = localeArray[i];
							Set v = (Set) m.get(group);
							if (v == null) {
								v = new HashSet();
								m.put(group, v);
							}
							v.add(u);
							// SurveyLog.logger.warning(group + " - " + u.email + "");
						}
					}
				}
			}
		} catch (SQLException se) {
			busted("SQL error querying users for getIntUsers - "
					+ DBUtils.unchainSqlException(se));
			throw new RuntimeException(
					"SQL error querying users for getIntUsers - "
							+ DBUtils.unchainSqlException(se));

		} finally {
			DBUtils.close(conn);
		}
		return m;
	}


    void writeRadio(WebContext ctx,String xpath,String type,String value,String checked) {
        writeRadio(ctx, xpath, type, value, checked.equals(value));        
    }

    void writeRadio(WebContext ctx,String xpath,String type,String value,boolean checked) {
        ctx.println("<input type=radio name='" + fieldsToHtml(xpath,type) + "' value='" + value + "' " +
                    (checked?" CHECKED ":"") + "/>");
    }


    public static final com.ibm.icu.text.Transliterator hexXML
        = com.ibm.icu.text.Transliterator.getInstance(
        "[^\\u0009\\u000A\\u0020-\\u007E\\u00A0-\\u00FF] Any-Hex/XML");

    // like above, but quote " 
    public static final com.ibm.icu.text.Transliterator quoteXML 
        = com.ibm.icu.text.Transliterator.getInstance(
         "[^\\u0009\\u000A\\u0020-\\u0021\\u0023-\\u007E\\u00A0-\\u00FF] Any-Hex/XML");

    public static int usedK() {
        Runtime r = Runtime.getRuntime();
        double total = r.totalMemory();
        total = total / 1024;
        double free = r.freeMemory();
        free = free / 1024;
        return (int)(Math.floor(total-free));
    }


    protected static void busted(String what) {
        busted(what, null, null);
    }
    
    /**
     * Report an error with a SQLException
     * @param what the error
     * @param se the SQL Exception
     */
    protected static void busted(String what, SQLException se) {
        busted(what, se, DBUtils.unchainSqlException(se));
    }
    
    protected static void busted(String what, Throwable t) {
        if(t instanceof SQLException) {
            busted(what, (SQLException)t);
        } else {
            busted(what, t, getThrowableStack(t));
        }
    }
    
    protected static void busted(String what, Throwable t, String stack) {
        SurveyLog.logException(t,what,stack);
        SurveyLog.logger.warning("SurveyTool busted: " + what + " ( after " +pages +"html+"+xpages+"xml pages served,  " + getGuestsAndUsers()  + ")");
        try {
            throw new InternalError("broke here");
        } catch(InternalError e) {
            e.printStackTrace();
        }
        if(!isBusted()) { // Keep original failure message.
            isBusted = what;
            if(stack == null) {
                stack = "(no stack)\n";
            }
            isBustedStack = stack + "\n"+"["+new Date().toGMTString()+"] "; 
            isBustedThrowable = t;
            isBustedTimer = new ElapsedTimer();
        } else { 
            SurveyLog.logger.warning("[was already busted, not overriding old message.]");
        }
        SurveyLog.logger.severe(what);
    }

    private void appendLog(WebContext ctx, String what) {
        String ipInfo =  ctx.userIP();
        appendLog(what, ipInfo);
    }

    public void appendLog(String what) {
        SurveyLog.logger.info(what);
    }

    public synchronized void appendLog(String what, String ipInfo) {
        SurveyLog.logger.info(what + " [@" + ipInfo + "]");
    }

    TreeMap allXpaths = new TreeMap();    
    public static Set draftSet = Collections.synchronizedSet(new HashSet());


    static final public String[] distinguishingAttributes =  { "key", "registry", "alt", "iso4217", "iso3166", "type", "default",
        "measurementSystem", "mapping", "abbreviationFallback", "preferenceOrdering" };

    static int xpathCode = 0;

    /**
    * convert a XPATH:TYPE form to an html field.
     * if type is null, means:  hash the xpath
     */
    String fieldsToHtml(String xpath, String type)
    {
        if(type == null) {
            String r = (String)allXpaths.get(xpath);
            if(r == null) synchronized(allXpaths) {
                // we've found a totally new xpath. Mint a new key.
                r = CookieSession.j + "Y" + CookieSession.cheapEncode(xpathCode++);
                allXpaths.put(xpt.poolx(xpath), r);
            }
            return r;
        } else {
            return xpath + "/" + type;
        }
    }

    static long shortN = 0;
    static final int MAX_CHARS = 100;
    static final int LARGER_MAX_CHARS = 256;
    static final String SHORT_A = "(Click to show entire message.)";
    static final String SHORT_B = "(hide.)";

    public static final String QUERY_FIELDHASH = "fhash";

	public static final String QUERY_XFIND = "xfind";
    static void printShortened(WebContext ctx, String str) {
        ctx.println(getShortened(str));
    }
    
    private static void printShortened(WebContext ctx, String str, int max) {
        ctx.println(getShortened(str, max));
    }
    
    private static String getShortened(String str) {
        return getShortened(str, MAX_CHARS);        
    }
    
    private static synchronized String  getShortened(String str, int max) {
        if(str.length()<(max+1+SHORT_A.length())) {
            return (str);
        } else {
            int cutlen = max;
            String key = CookieSession.cheapEncode(shortN++);
            int newline = str.indexOf('\n');
            if((newline > 2) && (newline < cutlen)) {
                cutlen = newline;
            }
            newline = str.indexOf("Exception:");
            if((newline > 2) && (newline < cutlen)) {
                cutlen = newline;
            }
            newline = str.indexOf("Message:");
            if((newline > 2) && (newline < cutlen)) {
                cutlen = newline;
            }
            newline = str.indexOf("<br>");
            if((newline > 2) && (newline < cutlen)) {
                cutlen = newline;
            }
            newline = str.indexOf("<p>");
            if((newline > 2) && (newline < cutlen)) {
                cutlen = newline;
            }
            return getShortened(str.substring(0,cutlen), str, key); 
        }
    }

    private void printShortened(WebContext ctx, String shortStr, String longStr, String warnHash ) {
            ctx.println(getShortened(shortStr, longStr, warnHash));
    }

    private static String getShortened(String shortStr, String longStr, String warnHash ) {
        return  ("<span id='h_ww"+warnHash+"'>" + shortStr + "... ") +
                ("<a href='javascript:show(\"ww" + warnHash + "\")'>" + 
                    SHORT_A+"</a></span>") +
                ("<!-- <noscript>Warning: </noscript> -->" + 
                    "<span style='display: none'  id='ww" + warnHash + "'>" +
                    longStr + "<a href='javascript:hide(\"ww" + warnHash + "\")'>" + 
                    SHORT_B+"</a></span>");
    }


    Hashtable xpathWarnings = new Hashtable();

    String getWarning(String locale, String xpath) {
        return (String)xpathWarnings.get(locale+" "+xpath);
    }


    boolean readWarnings() {
        int lines  = 0;
        try {
            BufferedReader in
            = BagFormatter.openUTF8Reader(cldrHome, "surveyInfo.txt");
            String line;
            while ((line = in.readLine())!=null) {
                lines++;
                if((line.length()<=0) ||
                   (line.charAt(0)=='#')) {
                    continue;
                }
                String[] result = line.split("\t");
                // result[0];  locale
                // result[1];  xpath
                // result[2];  warning
                xpathWarnings.put(result[0] + " /" + result[1], result[2]);
            }
        } catch (java.io.FileNotFoundException t) {
            //            SurveyLog.logger.warning(t.toString());
            //            t.printStackTrace();
            //logger.warning("Warning: Can't read xpath warnings file.  " + cldrHome + "/surveyInfo.txt - To remove this warning, create an empty file there.");
            return true;
        }  catch (java.io.IOException t) {
            SurveyLog.logger.warning(t.toString());
            t.printStackTrace();
            busted("Error: trying to read xpath warnings file.  " + cldrHome + "/surveyInfo.txt");
            return true;
        }
        
        //        SurveyLog.logger.warning("xpathWarnings" + ": " + lines + " lines read.");
        return true;
    }

    private static Hashtable stringHash = new Hashtable();

    static int stringHashIdentity = 0; // # of x==y hits
    static int stringHashHit = 0;

    static final String pool(String x) {
        if(x==null) {
            return null;
        }
        String y = (String)stringHash.get(x);
        if(y==null) {
            stringHash.put(x,x);
            return x;
        } else {
            return y;
        }
    }

    public DBUtils dbUtils= null;
    
    private void doStartupDB()
    {
        CLDRProgressTask progress = openProgress("Database Setup");
        try {
            progress.update("begin.."); // restore
            dbUtils.startupDB(this, progress);
            // now other tables..
            progress.update("Setup databases "); // restore
            try {
                progress.update("Setup  "+UserRegistry.CLDR_USERS); // restore
                progress.update("Create UserRegistry  "+UserRegistry.CLDR_USERS); // restore
                reg = UserRegistry.createRegistry(SurveyLog.logger, this);
            } catch (SQLException e) {
                busted("On UserRegistry startup", e);
                return;
            }
            progress.update( "Create XPT"); // restore
            try {
                xpt = XPathTable.createTable(dbUtils.getDBConnection(), this);
            } catch (SQLException e) {
                busted("On XPathTable startup", e);
                return;
            }
            // note: make xpt before CLDRDBSource..
            progress.update("Create CLDR_DATA"); // restore
            try {
                setDBSourceFactory(new CLDRDBSourceFactory(this, fileBase, SurveyLog.logger, new File(homeFile, "vxpt")));
            } catch (SQLException e) {
                busted("On CLDRDBSource startup", e);
                return;
            }
            progress.update( "Create Vetting"); // restore
            try {
                vet = Vetting.createTable(SurveyLog.logger, this);
            } catch (SQLException e) {
                e.printStackTrace();
                busted("On Vetting startup", e);
                return;
            }
            progress.update("Tell DBFac the Vetter is Ready"); // restore
            try {
                getDBSourceFactory().vetterReady();
            } catch (Throwable e) {
                e.printStackTrace();
                busted("On Tell DBFac the Vetter is Ready startup", e);
                return;
            }
            progress.update("Create fora"); // restore
            try {
                fora = SurveyForum.createTable(SurveyLog.logger, dbUtils.getDBConnection(), this);
            } catch (SQLException e) {
                busted("On Fora startup", e);
                return;
            }
            progress.update(" DB setup complete."); // restore
        } finally {
            progress.close();
        }
    }    
    
    public static final String getThrowableStack(Throwable t) {
        try {
            StringWriter asString = new StringWriter();
            t.printStackTrace(new PrintWriter(asString));
            return asString.toString();
        } catch ( Throwable tt ) {
        	tt.printStackTrace();
            return("[[unable to get stack: "+tt.toString()+"]]");
        }
    }

    void doShutdownDB() {
        boolean gotSQLExc = false;
        
        try
        {
        	
        	closeOpenUserLocaleStuff(true);
        	
        	getDBSourceFactory().closeAllEntries();
            // shut down other connections
            try {
                CookieSession.shutdownDB();
            } catch(Throwable t) {
                t.printStackTrace();
                SurveyLog.logger.warning("While shutting down cookiesession ");
            }
            try {
                if(reg!=null) reg.shutdownDB();
            } catch(Throwable t) {
                t.printStackTrace();
                SurveyLog.logger.warning("While shutting down reg ");
            }
            try {
                if(xpt!=null) xpt.shutdownDB();
            } catch(Throwable t) {
                t.printStackTrace();
                SurveyLog.logger.warning("While shutting down xpt ");
            }
            try {
                if(vet!=null) vet.shutdownDB();
            } catch(Throwable t) {
                t.printStackTrace();
                SurveyLog.logger.warning("While shutting down vet ");
            }
            
            dbUtils.doShutdown();
            dbUtils = null;
        }
        catch (SQLException se)
        {
            gotSQLExc = true;
            SurveyLog.logger.info("DB: while shutting down: " + se.toString());
        }
    }

    private void closeOpenUserLocaleStuff(boolean closeAll) {
    	if(allUserLocaleStuffs.isEmpty()) return;
    	SurveyLog.logger.warning("Closing " + allUserLocaleStuffs.size() + " user files.");
    	for(UserLocaleStuff uf : allUserLocaleStuffs) {
    		if(!uf.isClosed()) {
    			uf.internalClose();
    		}
    	}
	}
    
	public static void main(String args[]) {
        SurveyLog.logger.info("Starting some test of SurveyTool locally....");
        try{
            cldrHome=getHome()+"/cldr";
            vap="testingvap";
            SurveyMain sm=new SurveyMain();
            SurveyLog.logger.info("sm created.");
            sm.doStartup();
            SurveyLog.logger.info("sm started.");
            sm.doStartupDB();
            SurveyLog.logger.info("DB started.");
            if(isBusted != null)  {
                SurveyLog.logger.warning("Survey Tool is down: " + isBusted);
                return;
            }
            
            SurveyLog.logger.warning("--- Starting processing of requests ---");
            SurveyLog.logger.warning("Mem: "+freeMem());
            CookieSession cs = new CookieSession(true, "0.0.0.0");
            for ( String arg : args ) {
                com.ibm.icu.dev.test.util.ElapsedTimer reqTimer = new com.ibm.icu.dev.test.util.ElapsedTimer();
                SurveyLog.logger.warning("***********\n* "+arg);
                if(arg.equals("-wait")) {
                    try {
                        SurveyLog.logger.warning("*** WAITING ***");
                        System.in.read();
                    } catch(Throwable t) {}
                    continue;
                } else if(arg.equals("-makeall")) {
                    WebContext xctx = new URLWebContext("http://127.0.0.1:8080/cldr-apps/survey" + "?");
                    xctx.sm = sm;
                    xctx.session=cs;
                    for(int jj=0;jj<5;jj++) {
	                    for(CLDRLocale locale : sm.getLocales()) {
	                        com.ibm.icu.dev.test.util.ElapsedTimer qt = new com.ibm.icu.dev.test.util.ElapsedTimer(locale.getBaseName()+"#"+Integer.toString(jj));
	                        xctx.setLocale(locale);
	                    	DataSection.make(xctx, locale, SurveyMain.GREGO_XPATH, false, "modern");
	                    	SurveyLog.logger.warning("Made: " + qt.toString() + " -- " + freeMem());
	                    }
                    }
                    continue;
                } else if(arg.equals("-makelots")) {
                    WebContext xctx = new URLWebContext("http://127.0.0.1:8080/cldr-apps/survey" + "?");
                    xctx.sm = sm;
                    xctx.session=cs;
	                CLDRLocale locale = CLDRLocale.getInstance("az_Arab");
                    xctx.setLocale(locale);
                    for(int jj=0;jj<50000;jj++) {
//	                    for(CLDRLocale locale : sm.getLocales()) {
	                        com.ibm.icu.dev.test.util.ElapsedTimer qt = new com.ibm.icu.dev.test.util.ElapsedTimer(locale.getBaseName()+"#"+Integer.toString(jj));
	                        xctx.setLocale(locale);
	                    	DataSection ds = DataSection.make(xctx, locale, SurveyMain.GREGO_XPATH, false,"modern");
	                        DataSection.DisplaySet set = ds.createDisplaySet(SortMode.getInstance(SurveyMain.PREF_SORTMODE_CODE_CALENDAR), null);
	                    	SurveyLog.logger.warning("Made: " + qt.toString() + " -- " + freeMem());
//	                    }
                    }
                    continue;
                } else if(arg.equals("-displots")) {
	                WebContext xctx = new URLWebContext("http://127.0.0.1:8080/cldr-apps/survey" + "?");
	                xctx.sm = sm;
	                xctx.session=cs;
	                CLDRLocale locale = CLDRLocale.getInstance("az_Arab");
                    xctx.setLocale(locale);
                	DataSection ds = DataSection.make(xctx, locale, SurveyMain.GREGO_XPATH, false,"modern");
                	long startTime = System.currentTimeMillis();
                    com.ibm.icu.dev.test.util.ElapsedTimer qt = new com.ibm.icu.dev.test.util.ElapsedTimer(locale.getBaseName());
	                for(int jj=0;jj<10000;jj++) {
                        DataSection.DisplaySet set = ds.createDisplaySet(SortMode.getInstance(SurveyMain.PREF_SORTMODE_CODE_CALENDAR), null);
                    	if((jj%1000)==1) {
                    		long nowTime = System.currentTimeMillis();
                    		long et = nowTime-startTime;
                    		double dps=  (((double)jj)/((double)et))*1000.0;
                    		SurveyLog.logger.warning("Made: " + qt.toString() + " -- " + freeMem() + " - " + set.rows.length + " - #"+jj + ":  "+dps+"/sec");
                    	}
	                }
	                continue;
                } else if (arg.equals("-regextst")) {
                	long startTime = System.currentTimeMillis();
	                for(int jj=0;jj<5000000;jj++) {
	                	SurveyMain.GREGO_XPATH.matches("calendar-.*\\|pattern\\|date-.*");
                    	if((jj%1000000)==1) {
                    		long nowTime = System.currentTimeMillis();
                    		long et = nowTime-startTime;
                    		double dps=  (((double)jj)/((double)et))*1000.0;
                    		SurveyLog.logger.warning("ONE: - - #"+jj + ":  "+dps+"/sec");
                    	}
	                }
	                startTime = System.currentTimeMillis();
	                Pattern pat = Pattern.compile("calendar-.*\\|pattern\\|date-.*");
	                for(int jj=0;jj<5000000;jj++) {
	                	pat.matcher(SurveyMain.GREGO_XPATH).matches();
                    	if((jj%1000000)==1) {
                    		long nowTime = System.currentTimeMillis();
                    		long et = nowTime-startTime;
                    		double dps=  (((double)jj)/((double)et))*1000.0;
                    		SurveyLog.logger.warning("TWO: - - #"+jj + ":  "+dps+"/sec");
                    	}
	                }
	                continue;
                }
                SurveyLog.logger.warning("Mem: "+freeMem());
                WebContext xctx = new URLWebContext("http://127.0.0.1:8080/cldr-apps/survey" + arg);
                xctx.sm = sm;
                xctx.session=cs;

                xctx.reqTimer = reqTimer;
                            
                if(xctx.field("dump").equals(vap)) {
                    sm.doAdminPanel(xctx);
                } else if(xctx.field("sql").equals(vap)) {
                    sm.doSql(xctx);
                } else {
                	try {
                		sm.doSession(xctx); // Session-based Survey main
                	} finally {
                		xctx.closeUserFile();
                	}
                }
                //xctx.close();
                SurveyLog.logger.warning("\n\n"+reqTimer+" for " + arg);
            }
            SurveyLog.logger.warning("--- Ending processing of requests ---");

            /*
            String ourXpath = "//ldml/numbers";
            
            SurveyLog.logger.info("xpath xpt.getByXpath("+ourXpath+") = " + sm.xpt.getByXpath(ourXpath));
            */
/*            
            
            if(arg.length>0) {
                WebContext xctx = new WebContext(false);
                xctx.sm = sm;
                xctx.session=new CookieSession(true);
                for(int i=0;i<arg.length;i++) {
                    SurveyLog.logger.info("loading stuff for " + arg[i]);
                    xctx.setLocale(new ULocale(arg[i]));
                    
                    WebContext ctx = xctx;
                    SurveyLog.logger.info("  - loading CLDRFile and stuff");
                    UserLocaleStuff uf = sm.getUserFile(...
                    CLDRFile cf = sm.getUserFile(ctx, (ctx.session.user==null)?null:ctx.session.user, ctx.getLocale());
                    if(cf == null) {
                        throw new InternalError("CLDRFile is null!");
                    }
                    CLDRDBSource ourSrc = (CLDRDBSource)ctx.getByLocale(USER_FILE + CLDRDBSRC); // TODO: remove. debuggin'
                    if(ourSrc == null) {
                        throw new InternalError("oursrc is null! - " + (USER_FILE + CLDRDBSRC) + " @ " + ctx.getLocale() );
                    }
                    CheckCLDR checkCldr =  (CheckCLDR)ctx.getByLocale(USER_FILE + CHECKCLDR+":"+ctx.defaultPtype());
                    if (checkCldr == null)  {
                        List checkCldrResult = new ArrayList();
                        SurveyLog.logger.warning("Initting tests . . .");
                        long t0 = System.currentTimeMillis();
        */
                      //  checkCldr = CheckCLDR.getCheckAll(/* "(?!.*Collision.*).*" */  ".*");
        /*                
                        checkCldr.setDisplayInformation(sm.getBaselineFile());
                        if(cf==null) {
                            throw new InternalError("cf was null.");
                        }
                        checkCldr.setCldrFileToCheck(cf, ctx.getOptionsMap(basicOptionsMap()), checkCldrResult);
                        SurveyLog.logger.warning("fileToCheck set . . . on "+ checkCldr.toString());
                        ctx.putByLocale(USER_FILE + CHECKCLDR+":"+ctx.defaultPtype(), checkCldr);
                        {
                            // sanity check: can we get it back out
                            CheckCLDR subCheckCldr = (CheckCLDR)ctx.getByLocale(SurveyMain.USER_FILE + SurveyMain.CHECKCLDR+":"+ctx.defaultPtype());
                            if(subCheckCldr == null) {
                                throw new InternalError("subCheckCldr == null");
                            }
                        }
                        if(!checkCldrResult.isEmpty()) {
                            ctx.putByLocale(USER_FILE + CHECKCLDR_RES+":"+ctx.defaultPtype(), checkCldrResult); // don't bother if empty . . .
                        }
                        long t2 = System.currentTimeMillis();
                        SurveyLog.logger.warning("Time to init tests " + arg[i]+": " + (t2-t0));
                    }
                    SurveyLog.logger.warning("getPod:");
                    xctx.getPod("//ldml/numbers");
*/
                
                /*
                    SurveyLog.logger.info("loading dbsource for " + arg[i]);
                    CLDRDBSource dbSource = CLDRDBSource.createInstance(sm.fileBase, sm.xpt, new ULocale(arg[i]),
                                                                        sm.getDBConnection(), null);            
                    SurveyLog.logger.info("dbSource created for " + arg[i]);
                    CLDRFile my = new CLDRFile(dbSource,false);
                    SurveyLog.logger.info("file created ");
                    CheckCLDR check = CheckCLDR.getCheckAll("(?!.*Collision.*).*");
                    SurveyLog.logger.info("check created");
                    List result = new ArrayList();
                    Map options = null;
                    check.setCldrFileToCheck(my, options, result); 
                    SurveyLog.logger.info("file set .. done with " + arg[i]);
                */
    /*
                }
            } else {
                SurveyLog.logger.info("No locales listed");
            }
    */
            
            SurveyLog.logger.info("done...");
            sm.doShutdownDB();
            SurveyLog.logger.info("DB shutdown.");
        } catch(Throwable t) {
            SurveyLog.logger.info("Something bad happened.");
            SurveyLog.logger.info(t.toString());
            t.printStackTrace();
        }
    }
    
    // ====== Utility Functions
    public static final String timeDiff(long a) {
        return timeDiff(a,System.currentTimeMillis());
    }
    
    public static final String timeDiff(long a, long b) {        
        final long ONE_DAY = 86400*1000;
        final long A_LONG_TIME = ONE_DAY*3;
        if((b-a)>(A_LONG_TIME)) {
            double del = (b-a);
            del /= ONE_DAY;
            int days = (int)del;
            return days + " days";
        } else {
          // round to even second, to avoid ElapsedTimer bug
          a -= (a%1000);
          b -= (b%1000);
          return ElapsedTimer.elapsedTime(a, b);
        }
    }
    
    public static     String shortClassName(Object o) {
        try {
            String cls = o.getClass().toString();
            int io = cls.lastIndexOf(".");
            if(io!=-1) {
                cls = cls.substring(io+1,cls.length());
            }  
            return cls;
        } catch (NullPointerException n) {
            return null;
        }
    }
    
    /**
     * get the local host 
     */
    public static String localhost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
   /* 
    public static String bugReplyUrl(String folder, int number, String subject) {
	return bugFeedbackUrl(subject);
    }
*/
    public static String bugFeedbackUrl(String subject) {
        return BUG_URL_BASE + "/newticket?component=survey&amp;summary="+java.net.URLEncoder.encode(subject);
    }
    
    
    // =============  Following have to do with "external errors file", an interim error handling mechanism
    
    /**
     * errors file 
     */
    private Hashtable<CLDRLocale, Set<Integer>> externalErrorSet = null;
    private long externalErrorLastMod = -1;
    private long externalErrorLastCheck = -1;
    private boolean externalErrorFailed = false;
         
    private synchronized boolean externalErrorRead() {
        if(externalErrorFailed) return false;
        String externalErrorName = cldrHome + "/" + "count.txt";

        long now = System.currentTimeMillis();
        
        if((now-externalErrorLastCheck) < 8000) {
            //SurveyLog.logger.warning("Not rechecking errfile- only been " + (now-externalErrorLastCheck) + " ms");
            if(externalErrorSet != null) {
                return true;
            } else {
                return false;
            }
        }

        externalErrorLastCheck = now;
        
        try {
            File extFile = new File(externalErrorName);
            
            if(!extFile.isFile() && !extFile.canRead()) {
                SurveyLog.logger.warning("Can't read counts file: " + externalErrorName);
                externalErrorFailed = true;
                return false;
            }
            
            long newMod = extFile.lastModified();
            
            if(newMod == externalErrorLastMod) {
                //SurveyLog.logger.warning("** e.e. file did not change");
                return true;
            }
            
            // ok, now read it
            BufferedReader in
               = new BufferedReader(new FileReader(extFile));
            String line;
            int lines=0;
            Hashtable<CLDRLocale, Set<Integer>> newSet = new Hashtable<CLDRLocale, Set<Integer>>();
            while ((line = in.readLine())!=null) {
                lines++;
                if((line.length()<=0) ||
                  (line.charAt(0)=='#')) {
                    continue;
                }
                try {
                    String[] result = line.split("\t");
                    CLDRLocale loc = CLDRLocale.getInstance(result[0].split(";")[0]);
                    String what = result[1];
                    String val = result[2];
                    
                    Set<Integer> aSet = newSet.get(loc);
                    if(aSet == null) {
                        aSet = new HashSet<Integer>();
                        newSet.put(loc, aSet);
                    }
                    
                    if(what.equals("path:")) {
                        aSet.add(xpt.getByXpath(val));
                    } else if(what.equals("count:")) {
                        int theirCount = new Integer(val).intValue();
                        if(theirCount != aSet.size()) {
                            SurveyLog.logger.warning(loc + " - count says " + val + ", we got " + aSet.size());
                        }
                    } else {
                        throw new IllegalArgumentException("Unknown parameter: " + what);
                    }
                } catch(Throwable t) {
                    SurveyLog.logger.warning("** " + externalErrorName +":"+ lines + " -  " + t.toString());
                    externalErrorFailed = true;
                    t.printStackTrace();
                    return false;  
                }
            }
            SurveyLog.logger.warning(externalErrorName + " - " + lines + " and " + newSet.size() + " locales loaded.");
            
            externalErrorSet = newSet;
            externalErrorLastMod = newMod;
            externalErrorFailed = false;
            return true;
        } catch(IOException ioe) {
            SurveyLog.logger.warning("Reading externalErrorFile: "  + "count.txt - " + ioe.toString());
            ioe.printStackTrace();
            externalErrorFailed = true;
            return false;
        }
    }
        
    public int externalErrorCount(CLDRLocale loc) {
        synchronized(this) {
            if(externalErrorRead()) {
                Set<Integer> errs =  externalErrorSet.get(loc);
                if(errs != null) {
                    return errs.size();
                } else {
                    return 0;
                }
            }
        }
        return -1;
    }
    
    public String externalErrorUrl(String groupName) {
        return "http://unicode.org/cldr/data/dropbox/gen/errors/"+groupName+".html";
    }

    // =============  Following have to do with phases

    public static boolean isPhaseSubmit() {
        return phase()==Phase.SUBMIT || phase()==Phase.BETA;
    }
    
    public static String getSaveButtonText() {
        return (phase()==Phase.BETA)?"Save [Note: SurveyTool is in BETA]":"Save Changes";
    }

    public static boolean isPhaseVetting() {
        return phase()==Phase.VETTING;
    }

    public static boolean isPhaseVettingClosed() {
        return phase()==Phase.VETTING_CLOSED;
    }

    public static boolean isPhaseClosed() {
        return (phase()==Phase.CLOSED) || (phase()==Phase.VETTING_CLOSED);
    }

    public static boolean isPhaseDisputed() {
        return phase()==Phase.DISPUTED;
    }

    public static boolean isPhaseFinalTesting() {
        return phase()==Phase.FINAL_TESTING;
    }

    public static boolean isPhaseReadonly() {
        return phase()==Phase.READONLY;
    }

    public static boolean isPhaseBeta() {
        return phase()==Phase.BETA;
    }

    public static Phase phase() {
        return currentPhase;
    }

    public static String getOldVersion() {
        return oldVersion;
    }

    public static String getNewVersion() {
        return newVersion;
    }

    public static String getProposedName() {
        return "Proposed&nbsp;"+getNewVersion();
    }
    
    Pattern ALLOWED_EMPTY = Pattern.compile("//ldml/fallback(?![a-zA-Z])");
    // TODO move to central location
    
    boolean choiceNotEmptyOrAllowedEmpty(String choice_r, String path) {
      return choice_r.length()>0 || ALLOWED_EMPTY.matcher(path).matches();
    }
	static String xmlescape(String str) {
	    if(str.indexOf('&')>=0) {
	        return str.replaceAll("&", "\\&amp;");
	    } else {
	        return str;
	    }
	}
	
	public static ULocale iso8601Locale = ULocale.forLanguageTag("en-US-POSIX-u-ca-iso8601");
	
	public static DateFormat getISODateFormat() {
		//return Calendar.getInstance(iso8601Locale).getDateTimeFormat(DateFormat.SHORT, DateFormat.SHORT, iso8601Locale);
		Calendar c = Calendar.getInstance(iso8601Locale);
		SimpleDateFormat sd = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		sd.setCalendar(c);
		sd.setTimeZone(TimeZone.getTimeZone("GMT"));
		return sd;
	}
	private static DateFormat myDateFormat = getISODateFormat();
	public synchronized static String formatDate(Date date) {
		return myDateFormat.format(date);
	}
	public static String formatDate() {
		return formatDate(new Date());
	}
	/**
	 * @return the fileBaseOld
	 */
	static String getFileBaseOld() {
		return fileBaseOld;
	}
	/**
	 * @param fileBaseOld the fileBaseOld to set
	 */
	public static void setFileBaseOld(String fileBaseOld) {
		SurveyMain.fileBaseOld = fileBaseOld;
	}
}
