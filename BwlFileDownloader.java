/**
 * BwlFileDownloader
 * 
 * By using Blueworks Live's REST API this application will get a list of
 * document attachments in the given account and download these files to the
 * local file system.
 * 
 * @author Martin Westphal, westphal@de.ibm.com
 * @version 1.1
 */
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

/**
 * 
 * Compile:
 *    javac -cp .;commons-io-2.4.jar;wink-json4j-1.3.0.jar BwlFileDownloader.java
 * 
 * Run it:
 *    java -cp .;commons-io-2.4.jar;wink-json4j-1.3.0.jar BwlFileDownloader <user> <password> <account> 
 * 
 */
public class BwlFileDownloader {

    // --- The Blueworks Live server info and login
    private final static String REST_API_SERVER = "https://www.blueworkslive.com";
    private static String REST_API_USERNAME = "";
    private static String REST_API_PASSWORD = "";
    private static String REST_API_ACCOUNT_NAME = "";

    // --- API call parameters
    private static String REST_API_FROM = "2012-01-01";

    // --- Configuration
    private static String PATH_OUTPUT = "./downloads";
    private static String TIME_FROM_FILE = "";
    private static String TIME_TO_FILE = "./timestamp.txt";
    
    private static String NAME_UNKNOWN = "other";  // name to be used if an element name is not given or can not be resolved
    private static boolean USE_SUBDIRECTORIES = false;
    private static boolean LIST_ONLY = false;
    private static boolean RENAME_FILE = false;
    private static boolean NOT_TODAY = false;
    
    // --- Usage
    private static String USAGE = "Usage: BwlFileDownloader <user> <password> <account> [optional_arguments]\n"
    		+ "Optional arguments:\n"
    		+ "  -h          This help message\n"
    		+ "  -d <path>   Directory to store downloads, default="+PATH_OUTPUT+"\n"
    		+ "  -f <file>   File to read 'from' date-time, default="+TIME_FROM_FILE+"\n"
    		+ "  -t <file>   File to write 'today' date-time, default="+TIME_TO_FILE+"\n"
    		+ "  -s          Use subdirectories, default="+USE_SUBDIRECTORIES+"\n"
    		+ "  -l          List files only, but do not download, default="+LIST_ONLY+"\n"
    		+ "  -r          Rename file if a file with the same name already exists, default="+RENAME_FILE+"\n"
    		+ "  -n          Do not download attachments from today. They will be covered with the next incremental download, default="+NOT_TODAY+"\n"
    		;
    
    private static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    public static void main(String[] args) {
    	int i = 3;
    	int fileCount = 0;
    	String arg;
    	if (args.length < i) printErrorAndExit("missing command line arguments, 3 arguments required");
    	REST_API_USERNAME = args[0];
    	REST_API_PASSWORD = args[1];
    	REST_API_ACCOUNT_NAME = args[2];
    	Date today = new Date();
        
    	while (i < args.length && args[i].startsWith("-")) {
            arg = args[i++];
    		if (arg.equals("-h")) printErrorAndExit("");
    		else if (arg.equals("-s")) {
    			USE_SUBDIRECTORIES = true;
            }
    		else if (arg.equals("-l")) {
    			LIST_ONLY = true;
            }
    		else if (arg.equals("-r")) {
    			RENAME_FILE = true;
            }
    		else if (arg.equals("-n")) {
    			NOT_TODAY = true;
            }
    		else if (arg.equals("-d")) {
                if (i < args.length) PATH_OUTPUT = args[i++];
                else printErrorAndExit("option -d requires a path"); 
            }
    		else if (arg.equals("-f")) {
                if (i < args.length) TIME_FROM_FILE = args[i++];
                else printErrorAndExit("option -f requires a filename"); 
            }
    		else if (arg.equals("-t")) {
                if (i < args.length) TIME_TO_FILE = args[i++];
                else printErrorAndExit("option -t requires a filename"); 
            }
    		else  {
    			printErrorAndExit("unknown command line option "+arg);
            }
    	}
    	
    	System.out.println("Downloading files from Blueworks Live account "+REST_API_ACCOUNT_NAME+" for user "+REST_API_USERNAME);
    	System.out.println("Will store files in directory: " + PATH_OUTPUT);
    	if (TIME_FROM_FILE.length()>0) {
    		REST_API_FROM = readDate(TIME_FROM_FILE);
        	System.out.println("From date: " + REST_API_FROM);
    	}
    	System.out.println("------------------------------------------------------------------------------");
    	

        try {
            InputStream restApiStream = getFileListData();
            try {
                JSONObject appListResult = new JSONObject(restApiStream);
            	//System.out.println(appListResult.toString(2));
                
                JSONArray files = (JSONArray) appListResult.get("files");
            	JSONObject file = null;
                for (Object objFile : files) {
                	file = (JSONObject)objFile; // contains: fileId, fileName, fileSize, uploadUserId, uploadDate, attachedToType, attachedToId
                	String fileId = file.getString("fileId");
                	String fileName = file.getString("fileName");
                	String type = file.has("attachedToType")?file.getString("attachedToType"):"other"; // process, policy, decision, post, instance, app, ???
                	String typeId = file.has("attachedToId")?file.getString("attachedToId"):null;
                	String uploadDate = file.getString("uploadDate");
                	String path = PATH_OUTPUT;
                	
                	path = FilenameUtils.concat(path, "");
                	
                	if (NOT_TODAY && uploadDate.startsWith(DATE_FORMAT.format(today))) {
                		System.out.println("INFO: Will skip this file because it is from today, id= "+fileId+" name="+fileName);
                		continue;
                	}
                	
                	/*
                	 * Store the file in a subdirectory named by the type and within in another subdirectory named by process, ...
                	 */
                	if (USE_SUBDIRECTORIES) {
            			String name = "";
                		path = FilenameUtils.concat(path, type);
                		try {
                			if (type.equalsIgnoreCase("process"))
                				name = getProcessName(typeId);
                			else if (type.equalsIgnoreCase("app"))
                				name = getAppNameByProcessId(typeId);
                			else if (type.equalsIgnoreCase("instance"))
                				name = getInstanceName(typeId);
                		} catch (Exception e) {
                			e.printStackTrace();
                			path = FilenameUtils.concat(path, NAME_UNKNOWN);
                		}
                		if (name != "") {
                			String subdir = toValidFileName(name);
            				path = FilenameUtils.concat(path, subdir);
                		}
                	}

                	if (LIST_ONLY) {
                		System.out.println("INFO: Would download file id="+fileId+" name="+fileName+" to "+path);
                	}
                	else {
                		System.out.println("INFO: Will download file id="+fileId+" name="+fileName+" to "+path);
                		InputStream inputStream = getFileData(fileId);
                		if (inputStream == null) {
                			System.out.println("ERROR: could not download file id="+fileId+" name="+fileName+" - will skip this file");
                		}
                		else if (writeFileData (inputStream,path,fileName)) {
                			fileCount++;
                			System.out.println("INFO: #"+fileCount+" downloaded file id="+fileId+" name="+fileName+" to "+PATH_OUTPUT);
                			System.out.println("FILEINFO: "+file.toString(0));
                			System.out.println("");
                		}
                	}
                	//System.out.println("Done");
                }
                
            } finally {
                restApiStream.close();
            	System.out.println("------------------------------------------------------------------------------");
            	System.out.println("Downloaded "+fileCount+" files to directory: " + PATH_OUTPUT);
            	if (TIME_TO_FILE.length()>0) writeDate(TIME_TO_FILE,today);
            	System.out.println("DONE");
                
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Call this method to print out an error message during command line parsing,
     * together with the USAGE information and exit.
     * Use an empty message to get USAGE only.
     * 
     * @param message the error message to print
     */
    private static void printErrorAndExit (String message) {
        if (message.length() > 0) System.err.println("ERROR: "+message);
        System.err.println(USAGE);
        System.exit(1);
    }
    
    /**
     * Read a date from a given file.
     * 
     * @param filename The file that contains the 'from' date.
     * @return the 'from' date as formatted text
     */
    private static String readDate(String filename) {
    	String from = "";
    	try {
			from = FileUtils.readFileToString(new File(filename));
		} catch (IOException e1) {
			printErrorAndExit("could not read file "+filename);
		}
    	try {
			DATE_FORMAT.parse(from);
		} catch (ParseException e) {
			printErrorAndExit("invalid date "+from);
		}
    	return from;
    }
    
    /**
     * Write a date to the given file.
     * 
     * @param filename The file to write a date to.
     * @param date The date.
     * @return the date as formatted text
     */
    private static String writeDate(String filename, Date date) {
    	String today = DATE_FORMAT.format(date);
    	try {
			FileUtils.writeStringToFile(new File(filename), today);
			System.out.println("Until today: "+today+" -> Date information written to file "+filename);
		} catch (IOException e) {
			System.err.println("WARNING: Could not write date information to file "+filename);
		}
    	return today;
    }
    
    /**
     * Generic call of the API resource "ListFiles".
     */
    private static InputStream getFileListData () throws IOException {
        StringBuilder appListUrlBuilder = new StringBuilder(REST_API_SERVER + "/scr/api/ListFiles");
        appListUrlBuilder.append("?account=").append(REST_API_ACCOUNT_NAME);
        appListUrlBuilder.append("&from=").append(REST_API_FROM);

        HttpURLConnection restApiURLConnection = getRestApiConnection(appListUrlBuilder.toString());
        if (restApiURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            System.err.println("Error calling the Blueworks Live REST API: " + restApiURLConnection.getResponseMessage());
            System.exit(1);
        }

        return restApiURLConnection.getInputStream();
    }
    
    /**
     * Map names (e.g. of a process) to a valid file name.
     * E.g. Windows does not allow some special characters: https://msdn.microsoft.com/en-us/library/aa365247%28VS.85%29
     * <>:"/\|?*
     * @param name the name to be mapped
     */
    public static String toValidFileName(String name)
    {
        return name.replaceAll("[:\\\\/*?|<>']", "-").replaceAll("[\"\n\r]", "");
    }
    

    /**
     * Call API to get the process name
     * 
     * @param processId the ID of the process
     * @throws JSONException 
     */
    private static String getProcessName (String processId) throws IOException, JSONException {
        StringBuilder appListUrlBuilder = new StringBuilder(REST_API_SERVER + "/scr/api/ProcessData");
        appListUrlBuilder.append("?account=").append(REST_API_ACCOUNT_NAME);
        appListUrlBuilder.append("&processId=").append(processId);
		
        //System.out.println("APICall: " + appListUrlBuilder.toString());
        HttpURLConnection restApiURLConnection = getRestApiConnection(appListUrlBuilder.toString());
        if (restApiURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        	System.err.println("ERROR:  Error calling the Blueworks Live REST API: " + restApiURLConnection.getResponseMessage());
        	return NAME_UNKNOWN;
        }
        InputStream restApiStream = restApiURLConnection.getInputStream();
        JSONObject appListResult = new JSONObject(restApiStream);
        JSONObject items = (JSONObject) appListResult.get("items");
        JSONObject item = (JSONObject) items.get(processId);
        String name = item.getString("name");
        return name;
    }

    /**
     * Call API to get the application name
     * 
     * @param appId the ID of the process application
     * @throws JSONException 
     */
    private static String getAppName (String appId) throws IOException, JSONException {
        StringBuilder appListUrlBuilder = new StringBuilder(REST_API_SERVER + "/scr/api/AppDetail");
        appListUrlBuilder.append("?account=").append(REST_API_ACCOUNT_NAME);
        appListUrlBuilder.append("&version=").append("20110917");
        appListUrlBuilder.append("&appId=").append(appId);
		
        //System.out.println("APICall: " + appListUrlBuilder.toString());
        HttpURLConnection restApiURLConnection = getRestApiConnection(appListUrlBuilder.toString());
        if (restApiURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        	System.err.println("ERROR:  Error calling the Blueworks Live REST API: " + restApiURLConnection.getResponseMessage());
        	return NAME_UNKNOWN;
        }
        InputStream restApiStream = restApiURLConnection.getInputStream();
        JSONObject appListResult = new JSONObject(restApiStream);
        JSONObject app = (JSONObject) appListResult.get("app");
        String name = app.getString("name");
        return name;
    }
    
    /**
     * Call API to get the application name
     * 
     * @param processId the ID of the work instance
     * @throws JSONException 
     */
    private static String getAppNameByProcessId (String processId) throws IOException, JSONException {
        StringBuilder appListUrlBuilder = new StringBuilder(REST_API_SERVER + "/scr/api/AppList");
        appListUrlBuilder.append("?account=").append(REST_API_ACCOUNT_NAME);
        appListUrlBuilder.append("&version=").append("20110917");
		
        //System.out.println("APICall: " + appListUrlBuilder.toString());
        HttpURLConnection restApiURLConnection = getRestApiConnection(appListUrlBuilder.toString());
        if (restApiURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        	System.err.println("ERROR:  Error calling the Blueworks Live REST API: " + restApiURLConnection.getResponseMessage());
        	return NAME_UNKNOWN;
        }
        InputStream restApiStream = restApiURLConnection.getInputStream();
        JSONObject appListResult = new JSONObject(restApiStream);
        JSONArray apps = (JSONArray) appListResult.get("apps");
        for (Object objFile : apps) {
        	JSONObject app = (JSONObject)objFile; // contains: id, processId, name, type, (new)
        	String thisId = app.getString("processId");
        	if (processId.compareTo(thisId)==0) {
        		String name = app.getString("name");
        		return name;
        	}
        }        
    	return NAME_UNKNOWN;
    }

    /**
     * Call API to get the instance name
     * 
     * @param workId the ID of the work instance
     * @throws JSONException 
     */
    private static String getInstanceName (String workId) throws IOException, JSONException {
        StringBuilder appListUrlBuilder = new StringBuilder(REST_API_SERVER + "/scr/api/WorkDetail");
        appListUrlBuilder.append("?account=").append(REST_API_ACCOUNT_NAME);
        appListUrlBuilder.append("&version=").append("20110917");
        appListUrlBuilder.append("&workId=").append(workId);
		
        //System.out.println("APICall: " + appListUrlBuilder.toString());
        HttpURLConnection restApiURLConnection = getRestApiConnection(appListUrlBuilder.toString());
        if (restApiURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        	System.err.println("ERROR:  Error calling the Blueworks Live REST API: " + restApiURLConnection.getResponseMessage());
        	return NAME_UNKNOWN;
        }
        InputStream restApiStream = restApiURLConnection.getInputStream();
        JSONObject appListResult = new JSONObject(restApiStream);
        JSONObject work = (JSONObject) appListResult.get("work");
        String name = work.getString("name");
        return name;
    }
    
    /**
     * Generic call of the API resource "FileDownload".
     * 
     * @param fileId the ID of the file to download
     */
    private static InputStream getFileData (String fileId) throws IOException {
        StringBuilder appListUrlBuilder = new StringBuilder(REST_API_SERVER + "/scr/api/FileDownload");
        appListUrlBuilder.append("?account=").append(REST_API_ACCOUNT_NAME);
        appListUrlBuilder.append("&fileItemId=").append(fileId);
		
        //System.out.println("APICall: " + appListUrlBuilder.toString());
        HttpURLConnection restApiURLConnection = getRestApiConnection(appListUrlBuilder.toString());
        if (restApiURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        	System.err.println("ERROR:  Error calling the Blueworks Live REST API: " + restApiURLConnection.getResponseMessage());
        	return null;
        }
        return restApiURLConnection.getInputStream();
    }
    
    /**
     * Store binary data in a file
     * 
     * @param inputStream an input stream with data to write
     * @param pathName directory to store the file, will be created, if it does not exist
     * @param fileName name of the output file
     */
    private static boolean writeFileData (InputStream inputStream, String pathName, String fileName) {
    	boolean ret = true;
    	OutputStream outputStream = null;
    	try {
    		File output = new File(pathName, fileName);
        	FileUtils.forceMkdir(new File(pathName));
        	if (RENAME_FILE && output.exists()) {
        		int i = 2;
        		boolean exists = true;
        		String base = FilenameUtils.getBaseName(fileName);
        		String ext = FilenameUtils.getExtension(fileName);
        		while (exists) {
        			fileName = base + "_" + i + "." + ext;
        			output = new File(pathName, fileName);
        			if (!output.exists()) exists = false;
        			else i++;
        		}
        		System.out.println("INFO: File with same name exists -> rename to "+fileName);
        	}
    		outputStream = new FileOutputStream(output);
    		int read = 0;
    		byte[] bytes = new byte[1024];
    		while ((read = inputStream.read(bytes)) != -1) {
    			outputStream.write(bytes, 0, read);
    		}
            	
        } catch (Exception e) {
			e.printStackTrace();
        	ret = false;
        } finally {
        	if (inputStream != null) {
        		try { inputStream.close(); } catch (IOException e) {
        			e.printStackTrace();
        		}
        	}
        	if (outputStream != null) {
        		try { outputStream.flush(); outputStream.close(); } catch (IOException e) {
        			e.printStackTrace();
        		}
        	}
        }
    	return ret;
    }
    
    
    /**
     * Set up the connection to a REST API including handling the Basic Authentication request headers that must be
     * present on every API call.
     * 
     * @param apiCall The URL string indicating the api call and parameters.
     * @return the open connection
     */
    public static HttpURLConnection getRestApiConnection(String apiCall) throws IOException {

        // Call the provided api on the Blueworks Live server
        URL restApiUrl = new URL(apiCall);
        HttpURLConnection restApiURLConnection = (HttpURLConnection) restApiUrl.openConnection();

        // Add the HTTP Basic authentication header which should be present on every API call.
        addAuthenticationHeader(restApiURLConnection);

        return restApiURLConnection;
    }

    /**
     * Add the HTTP Basic authentication header which should be present on every API call.
     * 
     * @param restApiURLConnection The open connection to the REST API.
     */
    private static void addAuthenticationHeader(HttpURLConnection restApiURLConnection) {
        String userPwd = REST_API_USERNAME + ":" + REST_API_PASSWORD;
        String encoded = DatatypeConverter.printBase64Binary(userPwd.getBytes());
        restApiURLConnection.setRequestProperty("Authorization", "Basic " + encoded);
    }
}
