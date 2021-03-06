/*
 * Copyright 2018 datagear.tech. All Rights Reserved.
 */

package org.datagear.persistence.support;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.datagear.connection.ConnectionOption;
import org.datagear.dbinfo.ColumnInfo;
import org.datagear.dbinfo.DatabaseInfoResolver;
import org.datagear.dbinfo.TableInfo;
import org.datagear.persistence.Dialect;
import org.datagear.persistence.DialectBuilder;
import org.datagear.persistence.DialectException;
import org.datagear.persistence.DialectSource;
import org.datagear.persistence.Order;
import org.datagear.persistence.SqlBuilder;
import org.datagear.persistence.UnsupportedDialectException;
import org.datagear.util.JdbcUtil;
import org.datagear.util.JdbcUtil.QueryResultSet;

/**
 * 默认{@linkplain DialectSource}。
 * 
 * @author datagear@163.com
 *
 */
public class DefaultDialectSource implements DialectSource
{
	protected static final String[] TABLE_TYPES = { "TABLE", "VIEW", "ALIAS" };

	protected static final String COLUMN_TABLE_NAME = "TABLE_NAME";

	protected static final String COLUMN_COLUMN_NAME = "COLUMN_NAME";

	private DatabaseInfoResolver databaseInfoResolver;

	private List<DialectBuilder> dialectBuilders;

	private boolean detection = true;

	private ConcurrentMap<String, DialectBuilder> dialectBuilderCache = new ConcurrentHashMap<String, DialectBuilder>();

	public DefaultDialectSource()
	{
		super();
	}

	public DefaultDialectSource(DatabaseInfoResolver databaseInfoResolver, List<DialectBuilder> dialectBuilders)
	{
		super();
		this.databaseInfoResolver = databaseInfoResolver;
		this.dialectBuilders = dialectBuilders;
	}

	public DatabaseInfoResolver getDatabaseInfoResolver()
	{
		return databaseInfoResolver;
	}

	public void setDatabaseInfoResolver(DatabaseInfoResolver databaseInfoResolver)
	{
		this.databaseInfoResolver = databaseInfoResolver;
	}

	public List<DialectBuilder> getDialectBuilders()
	{
		return dialectBuilders;
	}

	public void setDialectBuilders(List<DialectBuilder> dialectBuilders)
	{
		this.dialectBuilders = dialectBuilders;
	}

	public boolean isDetection()
	{
		return detection;
	}

	public void setDetection(boolean detection)
	{
		this.detection = detection;
	}

	@Override
	public Dialect getDialect(Connection cn) throws DialectException
	{
		if (this.dialectBuilders != null)
		{
			for (DialectBuilder dialectBuilder : this.dialectBuilders)
			{
				if (dialectBuilder.supports(cn))
					return dialectBuilder.build(cn);
			}
		}

		if (this.detection)
		{
			Dialect detectiveDialect = getDetectiveDialect(cn);

			if (detectiveDialect != null)
				return detectiveDialect;
		}

		throw new UnsupportedDialectException(ConnectionOption.valueOf(cn));
	}

	/**
	 * 试探{@linkplain Dialect}。
	 * 
	 * @param cn
	 * @return
	 * @throws DialectException
	 */
	protected Dialect getDetectiveDialect(Connection cn) throws DialectException
	{
		try
		{
			String cacheKey = getDialectCacheKey(cn);

			DialectBuilder cached = this.dialectBuilderCache.get(cacheKey);

			if (cached != null)
				return cached.build(cn);
			else
			{
				DatabaseMetaData databaseMetaData = cn.getMetaData();

				CombinedDialectBuilder combinedDialectBuilder = new CombinedDialectBuilder();

				if (this.dialectBuilders != null)
				{
					TestInfo testInfo = buildTestInfo(cn, databaseMetaData);

					if (testInfo != null)
					{
						for (DialectBuilder dialectBuilder : this.dialectBuilders)
						{
							Dialect dialect = null;
							try
							{
								dialect = dialectBuilder.build(cn);
							}
							catch (Exception e)
							{
								dialect = null;
							}

							// 试探能够成功的分页实现
							if (dialect != null && combinedDialectBuilder.getToPagingQuerySqlDialectBuilder() == null)
							{
								try
								{
									if (testDialectToPagingSql(cn, databaseMetaData, testInfo, dialect))
										combinedDialectBuilder.setToPagingQuerySqlDialectBuilder(dialectBuilder);
								}
								catch (Exception e)
								{
								}
							}
						}
					}
				}

				this.dialectBuilderCache.putIfAbsent(cacheKey, combinedDialectBuilder);

				return combinedDialectBuilder.build(cn);
			}
		}
		catch (SQLException e)
		{
			throw new DialectException(e);
		}
	}

	protected String getDialectCacheKey(Connection cn) throws SQLException
	{
		String key = JdbcUtil.getURLIfSupports(cn);

		if (key == null)
			key = cn.getClass().getName();

		return key;
	}

	/**
	 * 构建测试信息。
	 * <p>
	 * 如果数据库中没有表存在，此方法将返回{@code null}。
	 * </p>
	 * 
	 * @param cn
	 * @param databaseMetaData
	 * @return
	 */
	protected TestInfo buildTestInfo(Connection cn, DatabaseMetaData databaseMetaData)
	{
		TableInfo tableInfo = this.databaseInfoResolver.getRandomTableInfo(cn);

		if (tableInfo == null)
			return null;

		String tableName = tableInfo.getName();

		ColumnInfo columnInfo = this.databaseInfoResolver.getRandomColumnInfo(cn, tableName);

		if (columnInfo == null)
			return null;

		return new TestInfo(tableInfo.getName(), columnInfo.getName());
	}

	/**
	 * 测试{@linkplain Dialect#toPagingSql(SqlBuilder, SqlBuilder, Order[], long, int)}方法。
	 * 
	 * @param cn
	 * @param databaseMetaData
	 * @param testInfo
	 * @param dialect
	 * @return
	 * @throws Exception
	 */
	protected boolean testDialectToPagingSql(Connection cn, DatabaseMetaData databaseMetaData, TestInfo testInfo,
			Dialect dialect) throws Exception
	{
		String identifierQuote = databaseMetaData.getIdentifierQuoteString();

		String tableQuote = identifierQuote + testInfo.getTableName() + identifierQuote;
		String columnName = identifierQuote + testInfo.getOrderColumnName() + identifierQuote;

		SqlBuilder query = SqlBuilder.valueOf();
		query.sql("SELECT * FROM ").sql(tableQuote);

		Order[] orders = Order.asArray(Order.valueOf(columnName, Order.ASC));

		SqlBuilder pagingQuerySql = dialect.toPagingQuerySql(query, orders, 1, 5);

		executeQuery(cn, pagingQuerySql);

		return true;
	}

	/**
	 * 执行查询。
	 * 
	 * @param cn
	 * @param query
	 * @return
	 */
	protected void executeQuery(Connection cn, SqlBuilder query) throws Exception
	{
		PreparedStatement pst = null;
		ResultSet rs = null;

		try
		{
			QueryResultSet queryResultSet = JdbcUtil.executeQuery(cn, query.getSqlString(), query.getArgTypes(),
					query.getArgs());

			pst = queryResultSet.getPreparedStatement();
			rs = queryResultSet.getResultSet();
		}
		finally
		{
			JdbcUtil.closeResultSet(rs);
			JdbcUtil.closeStatement(pst);
		}
	}

	protected static class TestInfo
	{
		private String tableName;

		private String orderColumnName;

		public TestInfo()
		{
			super();
		}

		public TestInfo(String tableName, String orderColumnName)
		{
			super();
			this.tableName = tableName;
			this.orderColumnName = orderColumnName;
		}

		public String getTableName()
		{
			return tableName;
		}

		public void setTableName(String tableName)
		{
			this.tableName = tableName;
		}

		public String getOrderColumnName()
		{
			return orderColumnName;
		}

		public void setOrderColumnName(String orderColumnName)
		{
			this.orderColumnName = orderColumnName;
		}
	}

	protected static class CombinedDialect extends AbstractDialect
	{
		private Dialect toPagingQuerySqlDialect;

		public CombinedDialect()
		{
			super();
		}

		public CombinedDialect(String identifierQuote)
		{
			super(identifierQuote);
		}

		public Dialect getToPagingQuerySqlDialect()
		{
			return toPagingQuerySqlDialect;
		}

		public void setToPagingQuerySqlDialect(Dialect toPagingQuerySqlDialect)
		{
			this.toPagingQuerySqlDialect = toPagingQuerySqlDialect;
		}

		@Override
		public boolean supportsPagingSql()
		{
			return (this.toPagingQuerySqlDialect != null);
		}

		@Override
		public SqlBuilder toPagingQuerySql(SqlBuilder query, Order[] orders, long startRow, int count)
		{
			if (this.toPagingQuerySqlDialect == null)
				return null;

			return this.toPagingQuerySqlDialect.toPagingQuerySql(query, orders, startRow, count);
		}
	}

	protected static class CombinedDialectBuilder extends AbstractDialectBuilder
	{
		private DialectBuilder toPagingQuerySqlDialectBuilder;

		public CombinedDialectBuilder()
		{
			super();
		}

		public DialectBuilder getToPagingQuerySqlDialectBuilder()
		{
			return toPagingQuerySqlDialectBuilder;
		}

		public void setToPagingQuerySqlDialectBuilder(DialectBuilder toPagingQuerySqlDialectBuilder)
		{
			this.toPagingQuerySqlDialectBuilder = toPagingQuerySqlDialectBuilder;
		}

		@Override
		public Dialect build(Connection cn) throws DialectException
		{
			CombinedDialect dialect = new CombinedDialect();

			dialect.setIdentifierQuote(getIdentifierQuote(cn));

			if (this.toPagingQuerySqlDialectBuilder != null)
				dialect.setToPagingQuerySqlDialect(this.toPagingQuerySqlDialectBuilder.build(cn));

			return dialect;
		}

		@Override
		public boolean supports(Connection cn)
		{
			return false;
		}
	}
}
