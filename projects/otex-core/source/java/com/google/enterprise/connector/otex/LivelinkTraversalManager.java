// Copyright (C) 2007 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.otex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;

import com.google.enterprise.connector.spi.PropertyMap;
import com.google.enterprise.connector.spi.PropertyMapList;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.TraversalManager;
import com.google.enterprise.connector.spi.TraversalContext;
import com.google.enterprise.connector.spi.TraversalContextAware;
import com.google.enterprise.connector.spi.ValueType;
import com.google.enterprise.connector.otex.client.Client;
import com.google.enterprise.connector.otex.client.ClientFactory;
import com.google.enterprise.connector.otex.client.ClientValue;

/**
 * This implementation of <code>TraversalManager</code> requires
 * the <code>LAPI_DOCUMENTS.ListNodes</code> method, which
 * is an undocumented LAPI method.
 *
 * The SQL queries used here are designed to work with SQL Server 7.0
 * or later, and with Oracle 8i Release 2 (8.1.6). This enables the
 * code to work with Livelink 9.0 or later. The exception to this is
 * that Sybase, which is supported by Livelink 9.2.0.1 and earlier, is
 * not supported here.
 */
class LivelinkTraversalManager
        implements TraversalManager, TraversalContextAware {

    /** The logger for this class. */
    private static final Logger LOGGER =
        Logger.getLogger(LivelinkTraversalManager.class.getName());

    /**
     * The primary store for property names that we want to map from
     * the database to the PropertyMap. This is an array of
     * <code>Field</code> objects that include the record array field
     * name, the record array field type, and the Google and Livelink
     * property names. If no property names are provided, then the
     * field will not be returned in the property map.
     */
    private static final Field[] FIELDS;

    static {
        // ListNodes requires the DataID and PermID columns to be
        // included here. This implementation requires DataID,
        // ModifyDate, MimeType, Name, SubType, OwnerID, and DataSize.
        ArrayList list = new ArrayList();

        list.add(new Field("DataID", ValueType.LONG, "ID",
            SpiConstants.PROPNAME_DOCID));
        list.add(new Field("ModifyDate", ValueType.DATE, "ModifyDate",
            SpiConstants.PROPNAME_LASTMODIFIED));
        list.add(new Field("MimeType", ValueType.STRING, "MimeType",
            SpiConstants.PROPNAME_MIMETYPE));

        list.add(new Field("DComment", ValueType.STRING, "Comment"));
        list.add(new Field("CreateDate", ValueType.DATE, "CreateDate"));
        list.add(new Field("OwnerName", ValueType.STRING, "CreatedBy"));
        list.add(new Field("Name", ValueType.STRING, "Name"));
        list.add(new Field("SubType", ValueType.LONG, "SubType"));
        list.add(new Field("OwnerID", ValueType.LONG, "VolumeID"));
        list.add(new Field("UserID", ValueType.STRING, "UserID"));

        list.add(new Field("DataSize"));
        list.add(new Field("PermID"));

        FIELDS = (Field[]) list.toArray(new Field[0]);
    }


    /** The connector contains configuration information. */
    private final LivelinkConnector connector;

    /** The client provides access to the server. */
    private final Client client;

    private final Object selectList;

    /**
     * The condition for excluding content from the traversal. This
     * condition is configured in the same way as the
     * LivelinkExtractor configuration in opentext.ini.
     *
     * @see #getExcluded
     */
    private final String excluded;

    /**
     * The condition for including content in the traversal.
     *
     * @see #getIncluded
     */
    private final String included;

    /** A concrete strategy for retrieving the content from the server. */
    private final ContentHandler contentHandler;

    /** The number of results to return in each batch. */
    /* TODO: Configurable default value. */
    private volatile int batchSize = 100;

    /** The database type, either SQL Server or Oracle. */
    /* XXX: We could use the state or strategy pattern if this gets messy. */
    private final boolean isSqlServer;

    /** The TraversalContext from TraversalContextAware Interface */
    private TraversalContext traversalContext = null;


    LivelinkTraversalManager(LivelinkConnector connector,
            ClientFactory clientFactory) throws RepositoryException {
        this.connector = connector;
        client = clientFactory.createClient();

        isSqlServer = isSqlServer();
        selectList = getSelectList();
        included = getIncluded();
        excluded = getExcluded();
        contentHandler = getContentHandler();
    }


    /**
     * Determines whether the database type is SQL Server or Oracle.
     *
     * @return <code>true</code> for SQL Server, or <code>false</code>
     * for Oracle.
     */
    private boolean isSqlServer() throws RepositoryException {
        String servtype = connector.getServtype();
        if (servtype == null) {
            // Autodetection of the database type. First, ferret out
            // generic errors when connecting or using ListNodes.
            String query = "1=1"; // ListNodes requires a WHERE clause.
            String[] columns = { "42" };
            ClientValue results = client.ListNodes(query, "KDual", columns);

            // Then check an Oracle-specific query.
            boolean isOracle;
            try {
                results = client.ListNodes(query, "dual", columns);
                isOracle = true;
            } catch (RepositoryException e) {
                isOracle = false;
            }
            if (LOGGER.isLoggable(Level.CONFIG)) {
                LOGGER.config("AUTO DETECT SERVTYPE: " +
                    (isOracle ? "Oracle" : "MSSQL"));
            }
            return !isOracle;
        } else {
            // This is basically startsWithIgnoreCase.
            boolean matches = servtype.regionMatches(true, 0, "MSSQL", 0, 5);
            if (LOGGER.isLoggable(Level.CONFIG)) {
                LOGGER.config("CONFIGURED SERVTYPE: " +
                    (matches ? "MSSQL" : "Oracle"));
            }
            return matches;
        }
    }


    /**
     * Gets the select list for the needed fields.
     *
     * @return an <code>ArrayList</code> for SQL Server, or a string
     * for Oracle.
     */
    private Object getSelectList() {
        if (isSqlServer) {
            ArrayList temp = new ArrayList(FIELDS.length);
            for (int i = 0; i < FIELDS.length; i++) {
                if (FIELDS[i].fieldName != null)
                    temp.add(FIELDS[i].fieldName);
            }
            return temp;
        } else {
            StringBuffer buffer = new StringBuffer();
            for (int i = 0; i < FIELDS.length; i++) {
                if (FIELDS[i].fieldName != null) {
                    buffer.append(',');
                    buffer.append(FIELDS[i].fieldName);
                }
            }
            return buffer.substring(1);
        }
    }

    /**
     * Gets a SQL conditional expression that excludes any
     * nodes other than those explicitly given in the list of
     * included nodes, including descendants of those nodes.
     *
     * This returns a SQL expression of the form:
     * <pre>
     *     (DataID in (<em>includedLocationNodes</em>) or
     *      DataID in
     *         (select DataID from DTreeAncestors where AncestorID in
     *             <em>includedLocationNodes</em>))
     * </pre>
     * if includedLocationNodes is empty, returns null
     *
     * @return the SQL conditional expression, or null
     * @throws RepositoryException if an error occurs getting the
     *         includedLocationNodes
     */
    String getIncluded() throws RepositoryException {
        StringBuffer buffer = new StringBuffer();

        String includedLocationNodes = connector.getIncludedLocationNodes();
        if (includedLocationNodes != null &&
            includedLocationNodes.length() > 0 ) {
            buffer.append("(DataID in (");
            buffer.append(includedLocationNodes);
            buffer.append(") or DataID in (SELECT DataID from ");
            buffer.append("DTreeAncestors where AncestorID in (");
            buffer.append(includedLocationNodes);
            buffer.append(")))");
        }

        String included = (buffer.length() > 0) ? buffer.toString() : null;
        if (LOGGER.isLoggable(Level.FINER))
            LOGGER.finer("INCLUDED: " + included);
        return included;
    }


    /**
     * Gets a SQL conditional expression that excludes nodes that
     * should not be traversed. This returns a SQL expression of the
     * form
     *
     * <pre>
     *     SubType not in (<em>excludedNodeTypes</em>) and
     *     DataID not in
     *         (select DataID from DTreeAncestors where AncestorID in
     *             (<em>excludedVolumeNodes</em>,
     *                 <em>excludedLocationNodes</em>))
     * </pre>
     *
     * where <em>excludedVolumeNodes</em> is obtained from
     *
     * <pre>
     *     select DataID from DTree where SubType in
     *         (<em>excludedVolumeTypes</em>)
     * </pre>
     *
     * The returned expression is simplified in the obvious way when
     * one or more of the configuration parameters is null or empty.
     *
     * @return the SQL conditional expression
     * @throws RepositoryException if an error occurs executing the
     * excluded volume types query
     */
    /* This method has package access so that it can be unit tested. */
    String getExcluded() throws RepositoryException {
        StringBuffer buffer = new StringBuffer();

        boolean hasNodeTypes;
        String excludedNodeTypes = connector.getExcludedNodeTypes();
        if (excludedNodeTypes != null &&
                excludedNodeTypes.length() > 0) {
            hasNodeTypes = true;
            buffer.append("SubType not in (");
            buffer.append(excludedNodeTypes);
            buffer.append(')');
        } else
            hasNodeTypes = false;

        ClientValue volumes;
        String excludedVolumeTypes = connector.getExcludedVolumeTypes();
        if (excludedVolumeTypes != null &&
                excludedVolumeTypes.length() > 0) {
            String query = "SubType in (" + excludedVolumeTypes + ")";
            String view = "DTree";
            String[] columns = { "DataID", "PermID" };
            ClientValue results = client.ListNodes(query, view, columns);
            volumes = (results.size() == 0) ? null : results;
        } else
            volumes = null;

        String locations;
        String excludedLocationNodes = connector.getExcludedLocationNodes();
        if (excludedLocationNodes != null &&
                excludedLocationNodes.length() > 0) {
            locations = excludedLocationNodes;
        } else
            locations = null;

        if (volumes != null || locations != null) {
            if (hasNodeTypes)
                buffer.append(" and ");

            buffer.append("DataID not in (select DataID from ");
            buffer.append("DTreeAncestors where AncestorID in (");

            if (volumes != null) {
                for (int i = 0; i < volumes.size(); i++) {
                    if (i > 0)
                        buffer.append(',');
                    buffer.append(volumes.toString(i, "DataID"));
                }
            }

            if (locations != null) {
                if (volumes != null)
                    buffer.append(',');
                buffer.append(locations);
            }

            buffer.append("))");
        }

        String excluded = (buffer.length() > 0) ? buffer.toString() : null;
        if (LOGGER.isLoggable(Level.FINER))
            LOGGER.finer("EXCLUDED: " + excluded);
        return excluded;
    }


    /**
     * Gets a new instance of the configured content handler class.
     *
     * @return a new instance of the configured content handler class
     * @throws RepositoryException if the class cannot be instantiated
     * or initialized
     */
    private ContentHandler getContentHandler() throws RepositoryException {
        ContentHandler contentHandler;
        String contentHandlerClass = connector.getContentHandler();
        try {
            contentHandler = (ContentHandler)
                Class.forName(contentHandlerClass).newInstance();
        } catch (Exception e) {
            throw new LivelinkException(e, LOGGER);
        }
        contentHandler.initialize(connector, client);
        return contentHandler;
    }


    /**
     * Sets the batch size. This implementation limits the actual
     * batch size to 100,000.
     *
     * @param hint the new batch size
     * @throws IllegalArgumentException if the hint is less than zero
     */
    public void setBatchHint(int hint) {
        if (hint < 0)
            throw new IllegalArgumentException();
        else if (hint == 0)
            batchSize = 100; // We could ignore it, but we reset the default.
        else if (hint > 100000) // TODO: Configurable limit.
            batchSize = 100000;
        else
            batchSize = hint;
    }


    /** {@inheritDoc} */
    public PropertyMapList startTraversal() throws RepositoryException {
        String cp = connector.getStartCheckpoint();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("START @" +
                Integer.toHexString(System.identityHashCode(this)) +
                ((cp==null)?"":"\t(startDate Checkpoint: " + cp + ")"));
        }
        // startCheckpoint will either be an initial checkpoint or null
        return listNodes(cp);
    }


    /**
     * {@inheritDoc}
     * <p>
     * This implementation gets a string of the form "yyyy-MM-dd
     * HH:mm:ss,nnnnnn" where nnnnnn is the object ID of the item
     * represented by the property map.
     *
     * @param pm a property map
     * @return a checkpoint string for the given property map
     */
    public String checkpoint(PropertyMap pm) throws RepositoryException {
        String s = ((LivelinkPropertyMap) pm).checkpoint();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("CHECKPOINT: " + s + " @" +
                Integer.toHexString(System.identityHashCode(this)));
        }
        return s;
    }


    /** {@inheritDoc} */
    public PropertyMapList resumeTraversal(String checkpoint)
            throws RepositoryException {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("RESUME: " + checkpoint + " @" +
                Integer.toHexString(System.identityHashCode(this)));
        }
        return listNodes(checkpoint);
    }

    /** {@inheritDoc} */
    public void setTraversalContext(TraversalContext traversalContext) {
        this.traversalContext = traversalContext;
    }

    /**
     * This method uses <code>LAPI_DOCUMENTS.ListNodes</code>, which
     * is an undocumented LAPI method. This method essentially
     * executes a query of the form
     *
     * <pre>
     *     SELECT <em>columns</em> FROM <em>view</em> WHERE <em>query</em>
     * </pre>
     *
     * where <em>columns</em>, <em>view</em>, and <em>query</em> are
     * three of the parameters to <code>ListNodes</code>.
     *
     * <p>
     * We want to execute queries of the following form
     *
     * <pre>
     *     SELECT <em>columns</em> FROM WebNodes WHERE
     *     <em>after_last_checkpoint</em> ORDER BY ModifyDate, DataID
     * </pre>
     *
     * and only read the first <code>batchSize</code> rows. The ORDER
     * BY clause is passed to the <code>ListNodes</code> method as
     * part of the WHERE clause. The <em>after_last_checkpoint</em>
     * condition is empty for <code>startTraversal</code>, or
     *
     * <pre>
     *     ModifyDate > <em>X</em> or
     *         (ModifyDate = <em>X</em> and DataID > <em>Y</em>)
     * </pre>
     *
     * for <code>resumeTraversal</code> where <em>X</em> is the
     * ModifyDate of the checkpoint and <em>Y</em> is the DataID of
     * the checkpoint.
     *
     * <p>
     * The details are little messy because there is not a single
     * syntax supported by both Oracle and SQL Server for limiting the
     * number of rows returned. For Oracle, we use ROWNUM, and for SQL
     * Server, we use TOP, which requires SQL Server 7.0. The
     * <code>ROW_NUMBER()</code> function is supported by Oracle and
     * also by SQL Server 2005, but that's too limiting, and it's
     * likely to be much slower than TOP or ROWNUM. The standard SQL
     * <code>SET ROWCOUNT</em> statement is supported by SQL Server
     * 6.5 and by Sybase, but not by Oracle, and I don't know of a way
     * to execute it from LAPI. A final complication is that ROWNUM
     * limits the rows before the ORDER BY clause is applied, so a
     * subquery is needed to do the ordering before the limit is
     * applied.
     *
     * @param checkpoint a checkpoint string, or <code>null</code> if
     * a new traversal should be started
     * @return a batch of results starting at the checkpoint, if there
     * is one, or the beginning of the traversal order, otherwise
     */
    private PropertyMapList listNodes(String checkpoint)
            throws RepositoryException {
        ClientValue recArray;
        if (isSqlServer)
            recArray = listNodesSqlServer(checkpoint);
        else
            recArray = listNodesOracle(checkpoint);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("RESULTSET: " + recArray.size() + " rows. @" +
                Integer.toHexString(System.identityHashCode(this)));
        }
        return new LivelinkResultSet(connector, client, contentHandler,
            recArray, FIELDS, traversalContext);
    }


    /**
     * The sort order of the traversal. We need a complete ordering
     * based on the modification date, in order to get incremental
     * crawling without duplicates.
     *
     * @see #getRestriction
     */
    private static final String ORDER_BY = " order by ModifyDate, DataID";


    /**
     * Generates the portion of the SQL query that's common to both
     * Oracle and SQLServer.  That is, the
     * @return a string representing the common portion of the query.
     * @throws RepositoryException
     */
    private String getCommonConditions(String checkpoint)
        throws RepositoryException
    {
        StringBuffer commonConditions = new StringBuffer();
        boolean needAnd = false;
        if (included != null) {
            commonConditions.append(included);
            needAnd = true;
        }
        if (excluded != null) {
            if ( needAnd )
                commonConditions.append(" and ");
            commonConditions.append(excluded);
            needAnd = true;
        }
        if (checkpoint != null) {
            if ( needAnd )
                commonConditions.append(" and ");
            commonConditions.append(getRestriction(checkpoint));
        }
        return commonConditions.toString();
    }


    private ClientValue listNodesSqlServer(String checkpoint)
            throws RepositoryException {
        StringBuffer buffer = new StringBuffer();
        if ( checkpoint == null && included == null && excluded == null)
            buffer.append("1=1");
        else // add conditions (included/excluded/checkpoint)
            buffer.append(getCommonConditions(checkpoint));
        buffer.append(ORDER_BY);
        String query = buffer.toString();

        String view = "WebNodes";
        ArrayList selectArrayList = (ArrayList) selectList;
        String[] columns = (String[]) selectArrayList.toArray(
            new String[selectArrayList.size()]);
        columns[0] = "top " + batchSize + " " + columns[0];

        return client.ListNodes(query, view, columns);
    }


    /*
     * XXX: We could use FIRST_ROWS(<batchSize>), but I don't know how
     * important that is on this query.
     */
    private ClientValue listNodesOracle(String checkpoint)
            throws RepositoryException {
        String query = "rownum <= " + batchSize;
        StringBuffer buffer = new StringBuffer();
        buffer.append("(select ");
        buffer.append(selectList);
        buffer.append(" from WebNodes ");

        // add conditions, if necessary (included/excluded/checkpoint)
        if (checkpoint != null || included != null || excluded != null) {
            buffer.append("where ");
            buffer.append(getCommonConditions(checkpoint));
        }

        buffer.append(ORDER_BY);
        buffer.append(')');
        String view = buffer.toString();
        if (LOGGER.isLoggable(Level.FINEST))
            LOGGER.finest("ORACLE VIEW: " + view);
        String[] columns = new String[] { "*" };

        return client.ListNodes(query, view, columns);
    }


    /**
     * Gets a SQL condition representing the given checkpoint. This
     * condition depends on the sort order of the traversal.
     *
     * @param checkpoint
     * @return a SQL condition returning items following the checkpoint
     * @see #ORDER_BY
     */
    /*
     * The TIMESTAMP literal, part of the SQL standard, was first
     * supported by Oracle 9i, and not at all by SQL Server. SQL
     * Server doesn't require a prefix on timestamp literals, and
     * we're using TO_DATE with Oracle in order to work with Oracle
     * 8i, and therefore with Livelink 9.0 or later.
     *
     * TODO: Validate the checkpoint. We could move the validatation
     * to resumeTraversal, which is the only place a non-null
     * checkpoint could come from.
     */
    private String getRestriction(String checkpoint)
            throws RepositoryException {
        int index = checkpoint.indexOf(',');
        if (index == -1) {
            throw new LivelinkException("Invalid checkpoint " + checkpoint,
                LOGGER);
        } else {
            String modifyDate = checkpoint.substring(0, index);
            String dataId = checkpoint.substring(index + 1);
            if (isSqlServer) {
                return "ModifyDate > '" + modifyDate +
                    "' or (ModifyDate = '" + modifyDate +
                    "' and DataID > " + dataId + ')';
            } else {
                return "ModifyDate > TO_DATE('" + modifyDate +
                    "', 'YYYY-MM-DD HH24:MI:SS') or (ModifyDate = TO_DATE('" +
                    modifyDate + "', 'YYYY-MM-DD HH24:MI:SS') and DataID > " +
                    dataId + ')';
            }
        }
    }
}