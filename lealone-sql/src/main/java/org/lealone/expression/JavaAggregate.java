/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.expression;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;

import org.lealone.api.AggregateFunction;
import org.lealone.api.ErrorCode;
import org.lealone.command.Parser;
import org.lealone.command.dml.Select;
import org.lealone.dbobject.UserAggregate;
import org.lealone.dbobject.table.ColumnResolver;
import org.lealone.dbobject.table.TableFilter;
import org.lealone.engine.Session;
import org.lealone.message.DbException;
import org.lealone.util.StatementBuilder;
import org.lealone.value.DataType;
import org.lealone.value.Value;
import org.lealone.value.ValueNull;

/**
 * This class wraps a user-defined aggregate.
 */
public class JavaAggregate extends Expression {

    private final UserAggregate userAggregate;
    private final Select select;
    private final Expression[] args;
    private int[] argTypes;
    private int dataType;
    private Connection userConnection;
    private int lastGroupRowId;
    private AggregateFunction aggregateFunction;

    public JavaAggregate(UserAggregate userAggregate, Expression[] args, Select select) {
        this.userAggregate = userAggregate;
        this.args = args;
        this.select = select;
    }

    @Override
    public int getCost() {
        int cost = 5;
        for (Expression e : args) {
            cost += e.getCost();
        }
        return cost;
    }

    @Override
    public long getPrecision() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getDisplaySize() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getScale() {
        return DataType.getDataType(dataType).defaultScale;
    }

    @Override
    public String getSQL(boolean isDistributed) {
        StatementBuilder buff = new StatementBuilder();
        buff.append(Parser.quoteIdentifier(userAggregate.getName())).append('(');
        for (Expression e : args) {
            buff.appendExceptFirst(", ");
            buff.append(e.getSQL(isDistributed));
        }
        return buff.append(')').toString();
    }

    @Override
    public int getType() {
        return dataType;
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        switch (visitor.getType()) {
        case ExpressionVisitor.DETERMINISTIC:
            // TODO optimization: some functions are deterministic, but we don't
            // know (no setting for that)
        case ExpressionVisitor.OPTIMIZABLE_MIN_MAX_COUNT_ALL:
            // user defined aggregate functions can not be optimized
            return false;
        case ExpressionVisitor.GET_DEPENDENCIES:
            visitor.addDependency(userAggregate);
            break;
        default:
        }
        for (Expression e : args) {
            if (e != null && !e.isEverything(visitor)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level) {
        for (Expression arg : args) {
            arg.mapColumns(resolver, level);
        }
    }

    @Override
    public Expression optimize(Session session) {
        userConnection = session.createConnection(false);
        int len = args.length;
        argTypes = new int[len];
        int[] argSqlTypes = new int[len];
        for (int i = 0; i < len; i++) {
            Expression expr = args[i];
            args[i] = expr.optimize(session);
            int type = expr.getType();
            argTypes[i] = type;
            argSqlTypes[i] = DataType.convertTypeToSQLType(type);
        }
        try {
            AggregateFunction aggregate = getInstance();
            dataType = DataType.convertSQLTypeToValueType(aggregate.getType(argSqlTypes));
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return this;
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        for (Expression e : args) {
            e.setEvaluatable(tableFilter, b);
        }
    }

    private AggregateFunction getInstance() throws SQLException {
        if (aggregateFunction == null) {
            aggregateFunction = userAggregate.getInstance();
            aggregateFunction.init(userConnection);
        }
        return aggregateFunction;
    }

    @Override
    public Value getValue(Session session) {
        HashMap<Expression, Object> group = select.getCurrentGroup();
        if (group == null) {
            throw DbException.get(ErrorCode.INVALID_USE_OF_AGGREGATE_FUNCTION_1, getSQL());
        }
        try {
            AggregateFunction agg = (AggregateFunction) group.get(this);
            if (agg == null) {
                agg = getInstance();
            }
            Object obj = agg.getResult();
            if (obj == null) {
                return ValueNull.INSTANCE;
            }
            return DataType.convertToValue(session, obj, dataType);
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    @Override
    public void updateAggregate(Session session) {
        HashMap<Expression, Object> group = select.getCurrentGroup();
        if (group == null) {
            // this is a different level (the enclosing query)
            return;
        }

        int groupRowId = select.getCurrentGroupRowId();
        if (lastGroupRowId == groupRowId) {
            // already visited
            return;
        }
        lastGroupRowId = groupRowId;

        AggregateFunction agg = (AggregateFunction) group.get(this);
        try {
            if (agg == null) {
                agg = getInstance();
                group.put(this, agg);
            }
            Object[] argValues = new Object[args.length];
            Object arg = null;
            for (int i = 0, len = args.length; i < len; i++) {
                Value v = args[i].getValue(session);
                v = v.convertTo(argTypes[i]);
                arg = v.getObject();
                argValues[i] = arg;
            }
            if (args.length == 1) {
                agg.add(arg);
            } else {
                agg.add(argValues);
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

}
