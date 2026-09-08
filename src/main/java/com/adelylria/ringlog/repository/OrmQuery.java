package com.adelylria.ringlog.repository;

import java.sql.SQLException;
import java.util.List;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DatabaseResultsMapper;
import com.j256.ormlite.dao.GenericRawResults;

final class OrmQuery {

    private OrmQuery() {
    }

    static <T> List<T> list(
            Dao<?, ?> dao,
            String sql,
            DatabaseResultsMapper<T> mapper,
            String... arguments
    ) throws SQLException {
        try (GenericRawResults<T> results = dao.queryRaw(sql, mapper, arguments)) {
            return results.getResults();
        } catch (SQLException | RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SQLException("No se pudo cerrar el resultado de la consulta.", exception);
        }
    }
}
