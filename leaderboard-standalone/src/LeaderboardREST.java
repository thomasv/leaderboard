// leaderboard server with rest api, supporting json or jsonp

// rest server
import static spark.Spark.*;
import spark.*;

// mysql
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
//import java.sql.PreparedStatement;

// logging
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

// json
//import com.google.gson.Gson;
//import com.google.gson.JsonArray;
//import com.google.gson.JsonParser;
//import java.util.ArrayList;
//import java.util.Collection;
// json-minimal
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.ParseException;

// configuration file
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class LeaderboardREST {
	static String configFileName = "config.properties";
	static Logger logger;

	///////////////////////////////////////////////////////////////////////////////////////

	public static int toIntDef(String v, int defaultValue) {
		int result = defaultValue;
		try {
			result = Integer.parseInt(v);
		} catch (Exception e) {
		}
		return result;
	}

	public static String toSafeString(String s, int maxLength) { // this should return a string that is safe to use in sql inputs, preventing sql injection
		if (s == null) return null;

		try {
			s = java.net.URLDecoder.decode(s, "UTF-8"); // to enable spaces
		} catch (Exception e) {return null;};

		int len = s.length();
		if (len > maxLength) len = maxLength;
		StringBuilder res = new StringBuilder(len);
		for (int i=0; i<len; i++) {
			char ch = s.charAt(i);
			if (   ((ch >= 'a') && (ch <= 'z')) // be very careful when changing this! besides ',\,; there are also comment charaters etc.
				|| ((ch >= 'A') && (ch <= 'Z'))
				|| ((ch >= '0') && (ch <= '9'))
				|| (ch == '.') || (ch == ' ') )
			res.append(ch);
		}

		return res.toString();
	}

	///////////////////////////////////////////////////////////////////////////////////////

   public static void main(String[] args) {
    	Properties prop = new Properties();
		//load a properties file
		try {
			prop.load(new FileInputStream(configFileName));
		} catch (IOException ex) {
			System.out.println("error reading config file: " + configFileName);
			return;
		}

		//get the property values and print them
		final String url = prop.getProperty("url"); // jdbc:mysql://localhost:3306/mygame";
		final String user = prop.getProperty("user");
		final String password = prop.getProperty("password");
		final String logfile = prop.getProperty("logfile");
		System.out.println("read these config values:");
		System.out.println("url: " + url);
		System.out.println("user: " + user);
		System.out.println("password: " + password);
		System.out.println("logfile: " + logfile);

		///////////////////

		// init logger (to file)
		logger = Logger.getLogger("MyLog");  
		FileHandler fh;  

		if (logfile != null) try {
			// This block configure the logger with handler and formatter
			fh = new FileHandler(logfile, 100000, 1, true);
			logger.addHandler(fh);
			SimpleFormatter formatter = new SimpleFormatter();
			fh.setFormatter(formatter);
			// the following statement is used to log any messages
			logger.info("My first log");
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}  

		logger.info("//////////// LeaderboardREST server started /////////////");  

	  // for browser (FireFox) cross origin resource sharing (CORS) to confirm that PUT and DELETE work, too
      options(new Route("/lb/:gameID") {
         @Override
         public Object handle(Request request, Response response) {
			System.out.println("OPTIONS");
			response.header("Access-Control-Allow-Origin", "*");
			response.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
			response.header("Access-Control-Allow-Headers", "X-Requested-With");
			response.header("Access-Control-Max-Age", "100");
			response.status(204);
			return ("");
		}
	  });
/*
      put(new Route("/lb/:gameID") {
         @Override
         public Object handle(Request request, Response response) {
			System.out.println("12345");
			return ("");
		}
	  });
*/
		///////////////////

      post(new Route("/lb/:gameID/:playerID/score") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");

			String gameID = toSafeString(request.params(":gameID"), 30);
			String playerID = toSafeString(request.params(":playerID"), 30);
System.out.println(gameID);
System.out.println(playerID);

System.out.println("queryparams:");
System.out.println(request.queryParams());
System.out.println("body:");
System.out.println(request.body());

			// read other parameters
			String content = request.body();
			//String content = request.queryParams().toString(); // this wraps the content in an additional array '[...]'
			String names = "";
			String values = "";
			try {
				JsonObject requestObj = JsonObject.readFrom(content);
				//JsonObject requestObj = JsonArray.readFrom(content).get(0).asObject(); // strip the additional '[...]' introduced above
				JsonArray scoresArr = requestObj.get("scoreEntries").asArray();
				for (int i=0; i<scoresArr.size(); i++) {
					JsonObject scoreEntryObj = scoresArr.get(i).asObject();
					if (i>0) {
						names += ',';
						values += ',';
					}
					names += toSafeString(scoreEntryObj.get("name").asString(), 30);
					//values += scoreEntryObj.get("value").asInt(); // convert to int as safe method to prevent injection
					values += Integer.parseInt(scoreEntryObj.get("value").asString()); // convert to int as safe method to prevent injection
				}
				//JsonObject authObj = requestObj.get("authentication").asObject();
				//JsonObject userDataObj = requestObj.get("userData").asObject();
			} catch (ParseException ex) {
System.out.println("JSON parse exception: " + ex.getMessage());
System.out.println(content);
				response.status(400); // 400 bad request
				return "Cannot parse JSON payload. " + ex.getMessage();
			} catch (Exception ex) {
				response.status(500); // 500 internal server error
				System.out.println("submitscore: exception " + ex.getMessage());
				ex.printStackTrace();
				return "Cannot read input. " + ex.getMessage();
			}

System.out.println(names);
System.out.println(values);

			Connection con = null;
			Statement st = null;

			response.type("application/json");

			StringBuilder result = new StringBuilder(40);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(url, user, password);

				st = con.createStatement();

				String updstr = "INSERT INTO mygame." + gameID + " (playerID," + names + ") VALUES (\'" + playerID + "\'," + values + ");";
System.out.println(updstr);
System.out.println();

				st.executeUpdate(updstr);
				response.status(200); // 200 ok
			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return "";
		 }
      });

      get(new Route("/lb/:gameID/:playerID/rankingposition") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");
 
			String gameID = toSafeString(request.params(":gameID"), 30);
			String playerID = toSafeString(request.params(":playerID"), 30);

			// read other parameters
			String orderBy = toSafeString(request.queryParams("orderBy"), 30);
			if (orderBy == null) orderBy = "highscore";
			String higherIsBetterStr = request.queryParams("higherIsBetter");
			boolean higherIsBetter = (higherIsBetterStr==null) || (!higherIsBetterStr.equals("false")); // set to true (default) if not param set to false

			Connection con = null;
			Statement st = null;
			ResultSet rs = null;

			response.type("application/json");

			StringBuilder result = new StringBuilder(40);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(url, user, password);

				st = con.createStatement();

				String qustr = "SELECT x.position FROM (SELECT mygame." + gameID + ".highscore,mygame." + gameID + ".playerID,@rownum:=@rownum+1 AS POSITION from mygame." + gameID + " JOIN (select @rownum:=0) r ORDER by mygame." + gameID + "." + orderBy + (higherIsBetter?" DESC":"") +") x WHERE x.playerID=\'" + playerID + "\' order by position limit 1;";
System.out.println(qustr);
System.out.println();

				rs = st.executeQuery(qustr);

				ResultSetMetaData rsmd = rs.getMetaData();
				int columnCount = rsmd.getColumnCount();

				if (rs.next()) { // only first position
					String pos = rs.getString(1);
					if (pos != null) {
						response.status(200); // 200 ok
						result.append("{position:");
						result.append(pos);
						result.append('}');
					} else {
						// no such player id in database
						response.status(404); // 404 Not Found
					}
				}
				if (callback != null) {
					result.append(");");
				}

System.out.println(result.toString());
System.out.println();

			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (rs != null) {
						rs.close();
					}
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return result.toString();
		 }
      });


//		static class getRankedListParams {
//		}

      get(new Route("/lb/:gameID/rankedlist") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");
 
			String gameID = toSafeString(request.params(":gameID"), 30);

			// read other parameters
			int rankStart = toIntDef(request.queryParams("rankStart"), 0); // todo: check if 0 is ok as default?
			int rankEnd = toIntDef(request.queryParams("rankEnd"), 0); // todo: check if 0 is ok as default?
			String orderBy = toSafeString(request.queryParams("orderBy"), 30);
			if (orderBy == null) orderBy = "highscore";

			Connection con = null;
			Statement st = null;
			//PreparedStatement st = null;
			ResultSet rs = null;

			//response.type("text/xml");
			response.type("application/json");

			StringBuilder result = new StringBuilder(400);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(url, user, password);

				String qustr = "SELECT * FROM mygame." + gameID + " ORDER BY " + orderBy + " DESC" + ((rankStart+rankEnd > 0) ? (" LIMIT " + rankStart + "," + rankEnd) : "") + ";";
System.out.println(qustr);
System.out.println();

				st = con.createStatement();
				rs = st.executeQuery(qustr);

				ResultSetMetaData rsmd = rs.getMetaData();
				int columnCount = rsmd.getColumnCount();

				result.append('[');
				boolean firstRow = true;
				while (rs.next()) {
					if (!firstRow) result.append(',');
					result.append('{');
					boolean firstOutpCol = true;
					for (int i=1; i<=columnCount; i++) {
						if (rs.getObject(i) != null) {
							if (!firstOutpCol) result.append(',');
							String column = rsmd.getColumnName(i);
							Object value = rs.getString(i);
							result.append('\"');
							result.append(column);
							result.append("\":\"");
							result.append(value);
							result.append('\"');
							firstOutpCol = false;
						}
					}
					result.append('}');
					firstRow = false;
				}
				result.append(']');
				if (callback != null) {
					result.append(");");
				}

				response.status(200); // 200 ok

System.out.println(result.toString());
System.out.println();

/*
    switch( rsmd.getColumnType( i ) ) {
      case java.sql.Types.ARRAY:
        obj.put(column_name, rs.getArray(column_name));     break;
      case java.sql.Types.BIGINT:
        obj.put(column_name, rs.getInt(column_name));       break;
      case java.sql.Types.BOOLEAN:
        obj.put(column_name, rs.getBoolean(column_name));   break;
      case java.sql.Types.BLOB:
        obj.put(column_name, rs.getBlob(column_name));      break;
      case java.sql.Types.DOUBLE:
        obj.put(column_name, rs.getDouble(column_name));    break;
      case java.sql.Types.FLOAT:
        obj.put(column_name, rs.getFloat(column_name));     break;
      case java.sql.Types.INTEGER:
        obj.put(column_name, rs.getInt(column_name));       break;
      case java.sql.Types.NVARCHAR:
        obj.put(column_name, rs.getNString(column_name));   break;
      case java.sql.Types.VARCHAR:
        obj.put(column_name, rs.getString(column_name));    break;
      case java.sql.Types.TINYINT:
        obj.put(column_name, rs.getInt(column_name));       break;
      case java.sql.Types.SMALLINT:
        obj.put(column_name, rs.getInt(column_name));       break;
      case java.sql.Types.DATE:
        obj.put(column_name, rs.getDate(column_name));      break;
      case java.sql.Types.TIMESTAMP:
        obj.put(column_name, rs.getTimestamp(column_name)); break;
      default:
        obj.put(column_name, rs.getObject(column_name));    break;
    }*/

/*
JSON example output:

[
  {"playerID":"2", "score":"300"},
  {"playerID":"3", "score":"800"},
  {"playerID":"4", "score":"600"}
]

*/
			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (rs != null) {
						rs.close();
					}
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return result.toString();
		 }
      });


		///////////////////

      put(new Route("/lb/:gameID") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");

			String gameID = toSafeString(request.params(":gameID"), 30);
System.out.println(gameID);

			Connection con = null;
			Statement st = null;

			response.type("application/json");

			StringBuilder result = new StringBuilder(40);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(url, user, password);

				st = con.createStatement();

				String updstr = "CREATE TABLE mygame." + gameID + " (playerID varchar(20) DEFAULT NULL, highscore int(11) DEFAULT NULL, userData blob);";
System.out.println(updstr);
System.out.println();

				st.executeUpdate(updstr);
				response.status(200); // 200 ok
			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return "";
		 }
      });

		///////////////////

      delete(new Route("/lb/:gameID") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");

			String gameID = toSafeString(request.params(":gameID"), 30);
System.out.println(gameID);

			Connection con = null;
			Statement st = null;

			response.type("application/json");

			StringBuilder result = new StringBuilder(40);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(url, user, password);

				st = con.createStatement();

				String updstr = "DROP TABLE mygame." + gameID;
System.out.println(updstr);
System.out.println();

				st.executeUpdate(updstr);
				response.status(200); // 200 ok
			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return "";
		 }
      });


  } // main

}
