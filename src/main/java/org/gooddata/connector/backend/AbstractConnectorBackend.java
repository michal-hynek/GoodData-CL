package org.gooddata.connector.backend;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import org.gooddata.connector.driver.AbstractSqlDriver;

import com.gooddata.connector.model.PdmSchema;
import com.gooddata.exception.InternalErrorException;
import com.gooddata.exception.ModelException;
import com.gooddata.integration.model.Column;
import com.gooddata.integration.model.DLI;
import com.gooddata.integration.model.DLIPart;
import com.gooddata.integration.rest.GdcRESTApiWrapper;
import com.gooddata.util.FileUtil;
import com.gooddata.util.JdbcUtil;
import org.gooddata.connector.driver.SqlDriver;

/**
 * GoodData abstract connector backend. This connector backend provides the base implementation that the specific
 * connector backends reuse.
 * Connector backend handles communication with the specific SQL database. Specifically it handles the DB connection
 * and other communication specifics of the DBMS. It uses the SQL driver that generates appropriate SQL dialect.
 *
 * @author zd <zd@gooddata.com>
 * @version 1.0
 */public abstract class AbstractConnectorBackend implements ConnectorBackend {

    private static Logger l = Logger.getLogger("org.gooddata.connector.backend");

    // SQL driver for executing DBMS specific SQL
    protected SqlDriver sg;

    // PDM schema
    private PdmSchema pdm;

    // Project id
    private String projectId;


    // MySQL username
    private String username;

    // MySQL password
    private String password;
    
    /**
     * The ZIP archive suffix
     */
    protected static final String DLI_ARCHIVE_SUFFIX = ".zip";


    /**
     * Constructor
     * @param username database backend username
     * @param username database backend password 
     * @throws IOException in case of an IO issue 
     */
    protected AbstractConnectorBackend(String username, String password) throws IOException {
        setUsername(username);
        setPassword(password);
    }

    /**
     * {@inheritDoc}
     */
    public abstract void dropSnapshots();
    
    

    /**
     * {@inheritDoc}
     */
    public void deploy(DLI dli, List<DLIPart> parts, String dir, String archiveName)
            throws IOException, ModelException {
        deploySnapshot(dli, parts, dir, archiveName, null);
    }

    /**
     * Adds CSV headers to all CSV files
     * @param parts the Data Loading Interface parts
     * @param dir target directory where the data package will be stored
     * @throws IOException IO issues
     */
    protected void addHeaders(List<DLIPart> parts, String dir) throws IOException {
        for(DLIPart part : parts) {
            String fn = part.getFileName();
            List<Column> cols = part.getColumns();
            String header = "";
            for(Column col : cols) {
                if(header != null && header.length() > 0) {
                    header += ","+col.getName();
                }
                else {
                    header += col.getName();                    
                }
            }
            File original = new File(dir + System.getProperty("file.separator") + fn);
            File tmpFile = FileUtil.appendCsvHeader(header, original);
            original.delete();
            tmpFile.renameTo(original);
        }
    }

    
    /**
     * {@inheritDoc}
     */
    public void deploySnapshot(DLI dli, List<DLIPart> parts, String dir, String archiveName, int[] snapshotIds)
            throws IOException, ModelException {
        loadSnapshot(parts, dir, snapshotIds);
        FileUtil.writeStringToFile(dli.getDLIManifest(parts), dir + System.getProperty("file.separator") +
                GdcRESTApiWrapper.DLI_MANIFEST_FILENAME);
        addHeaders(parts, dir);
        FileUtil.compressDir(dir, archiveName);
    }

    /**
     * {@inheritDoc}
     */
    public PdmSchema getPdm() {
        return pdm;
    }

    /**
     * {@inheritDoc}
     */
    public void setPdm(PdmSchema pdm) {
        this.pdm = pdm;
    }

    /**
     * {@inheritDoc}
     */
    public void initialize() throws ModelException {
        Connection con = null;
        try {
        	con = connect();
            if(!isInitialized()) {
                sg.executeSystemDdlSql(con);
            }
            sg.executeDdlSql(con, getPdm());    
        }
        catch (SQLException e) {
            throw new InternalError(e.getMessage());
        }
        finally {
            try {
                if (con != null && !con.isClosed())
                    con.close();
            }
            catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void transform() throws ModelException {
        Connection con = null;
        try {
            con = connect();
            sg.executeNormalizeSql(con, getPdm());
        }
        catch (SQLException e) {
            throw new RuntimeException("Error normalizing PDM Schema " + getPdm().getName() + " " + getPdm().getTables(), e);
        }
        finally {
            try {
                if (con != null && !con.isClosed())
                    con.close();
            }
            catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public String listSnapshots() throws InternalErrorException {
        String result = "ID        FROM ROWID        TO ROWID        TIME\n";
              result += "------------------------------------------------\n";
        Connection con = null;
        Statement s = null;
        ResultSet r = null;
        try {
            con = connect();
            s = con.createStatement();
            r = JdbcUtil.executeQuery(s, "SELECT id,firstid,lastid,tmstmp FROM snapshots");
            for(boolean rc = r.next(); rc; rc = r.next()) {
                int id = r.getInt(1);
                int firstid = r.getInt(2);
                int lastid = r.getInt(3);
                long tmstmp = r.getLong(4);
                Date tm = new Date(tmstmp);
                result += id + "        " + firstid + "        " + lastid + "        " + tm + "\n";
            }
        }
        catch (SQLException e) {
            throw new InternalErrorException(e.getMessage());
        }
        finally {
            try {
                if(r != null && !r.isClosed())
                    r.close();
                if (s != null && !s.isClosed())
                    s.close();
                if(con != null && !con.isClosed())
                    con.close();
            }
            catch (SQLException ee) {
               ee.printStackTrace();
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    public int getLastSnapshotId() throws InternalErrorException {
        Connection con = null;
        Statement s = null;
        ResultSet r = null;
        try {
            con = connect();
            s = con.createStatement();
            r = s.executeQuery("SELECT MAX(id) FROM snapshots");
            for(boolean rc = r.next(); rc; rc = r.next()) {
                int id = r.getInt(1);
                return id;
            }
        }
        catch (SQLException e) {
            throw new InternalErrorException(e.getMessage());
        }
        finally {
            try {
                if(r != null && !r.isClosed())
                    r.close();
                if(s != null && !s.isClosed())
                    s.close();
                if(con != null && !con.isClosed())
                    con.close();
            }
            catch (SQLException ee) {
                ee.printStackTrace();
            }
        }
        throw new InternalErrorException("Can't retrieve the last snapshot number.");
    }

    /**
     * {@inheritDoc}
     */
    public boolean isInitialized() {
        return exists("snapshots");
    }

    /**
     * {@inheritDoc}
     */
    public boolean exists(String tbl) {
        Connection con = null;
        try {
            con = connect();
            return sg.exists(con, tbl);
        }
        catch (SQLException e) {
        	throw new InternalError(e.getMessage());
		}
        finally {
            try {
                if(con != null && !con.isClosed())
                    con.close();
            }
            catch (SQLException ee) {
                ee.printStackTrace();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void extract(File dataFile) throws ModelException {
        Connection con = null;
        try {
            con = connect();
            sg.executeExtractSql(con, getPdm(), dataFile.getAbsolutePath());
        }
        catch (SQLException e) {
            throw new InternalError(e.getMessage());
        }
        finally {
            try {
                if (con != null && !con.isClosed())
                    con.close();
            }
            catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void load(List<DLIPart> parts, String dir) throws ModelException {
        loadSnapshot(parts, dir, null);
    }

    /**
     * {@inheritDoc}
     */
    public void loadSnapshot(List<DLIPart> parts, String dir, int[] snapshotIds) throws ModelException {
        Connection con = null;
        try {
            con = connect();
            // generate SELECT INTO CSV Derby SQL
            // the required data structures are taken for each DLI part
            for (DLIPart p : parts) {
                sg.executeLoadSql(con, getPdm(), p, dir, snapshotIds);
            }
        }
        catch (SQLException e) {
            throw new InternalError(e.getMessage());
        }
        finally {
            try {
                if (con != null && !con.isClosed())
                    con.close();
            }
            catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public String getUsername() {
        return username;
    }

    /**
     * {@inheritDoc}
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * {@inheritDoc}
     */
    public String getPassword() {
        return password;
    }

    /**
     * {@inheritDoc}
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * {@inheritDoc}
     */
    public String getProjectId() {
        return projectId;
    }
    
    /**
     * {@inheritDoc}
     */
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }
}
