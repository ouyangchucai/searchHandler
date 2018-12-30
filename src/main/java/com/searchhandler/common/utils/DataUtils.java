package com.searchhandler.common.utils;

import com.searchhandler.common.constants.ResultEnum;
import com.searchhandler.exception.SearchHandlerException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class DataUtils {

    public static final String DATE_YYYYMMDD_HHMMSS = "yyyy-MM-dd HH:mm:ss";
    public static final String DEFAULT_DATE_FORMAT = DATE_YYYYMMDD_HHMMSS;

    public static List splitWithoutEmpty(String baseStr, String splitKey) {
        return Optional.ofNullable(baseStr).map(x ->
                Stream.of(x.trim().split(splitKey))
                        .map(String::trim)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toList()))
                .orElse(new ArrayList());
    }

    public static void multiCheck(boolean throwAble, String errMsg, Object...objects) throws SearchHandlerException {
        if (null == objects || 0 == objects.length) {
            handleFailure(throwAble, errMsg);
            return;
        }

        long size = objects.length;
        for (Object object : objects) {
            if (null == object) {
                handleFailure(throwAble, errMsg);
            } else if (object instanceof String) {
                String str = DataUtils.handleNullValue(object, String.class, "").trim();
                if (StringUtils.isBlank(str)) {
                    handleFailure(throwAble, errMsg);
                }
            }
        }
    }

    private static void handleFailure(boolean throwAble, String errMsg) throws SearchHandlerException {
        if (throwAble) {
            throw new SearchHandlerException(ResultEnum.PARAMETER_CHECK, errMsg);
        } else {
            log.error(errMsg);
        }
    }

    public static <T> T getNotNullValue(Map base, String key, Class<T> clazz, Object defaultValue) {
        try {
            return handleNullValue(base.get(key), clazz, defaultValue);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Error happened when we try to get value," + e);
            return clazz.cast(defaultValue);
        }
    }

    public static <T> T handleNullValue(Object base, Class<T> clazz, Object defaultValue) {
        try {
            return clazz.cast(Optional.ofNullable(base).orElse(defaultValue));
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Error happened when we try to get value," + e);
            return clazz.cast(defaultValue);
        }
    }

    public static String formatDate(Date date) {

        return formatDate(date, DEFAULT_DATE_FORMAT);
    }

    public static String formatDate(Date date, String format) {
        if (null == date) {
            return null;
        }

        SimpleDateFormat sf = new SimpleDateFormat(format);
        return sf.format(date);
    }
}