package com.wristbridge.protocol.ancs

/** Numeric values from the appendix of Apple's ANCS specification. */
object AncsIds {
    const val CATEGORY_OTHER = 0
    const val CATEGORY_INCOMING_CALL = 1
    const val CATEGORY_MISSED_CALL = 2
    const val CATEGORY_VOICEMAIL = 3
    const val CATEGORY_SOCIAL = 4
    const val CATEGORY_SCHEDULE = 5
    const val CATEGORY_EMAIL = 6
    const val CATEGORY_NEWS = 7
    const val CATEGORY_HEALTH_AND_FITNESS = 8
    const val CATEGORY_BUSINESS_AND_FINANCE = 9
    const val CATEGORY_LOCATION = 10
    const val CATEGORY_ENTERTAINMENT = 11

    const val FLAG_SILENT = 1
    const val FLAG_IMPORTANT = 2
    const val FLAG_PRE_EXISTING = 4
    const val FLAG_POSITIVE_ACTION = 8
    const val FLAG_NEGATIVE_ACTION = 16

    const val COMMAND_GET_NOTIFICATION_ATTRIBUTES = 0

    const val ATTR_APP_IDENTIFIER = 0
    const val ATTR_TITLE = 1
    const val ATTR_SUBTITLE = 2
    const val ATTR_MESSAGE = 3
    const val ATTR_MESSAGE_SIZE = 4
    const val ATTR_DATE = 5
    const val ATTR_POSITIVE_ACTION_LABEL = 6
    const val ATTR_NEGATIVE_ACTION_LABEL = 7

    const val ERROR_UNKNOWN_COMMAND = 0xA0
    const val ERROR_INVALID_COMMAND = 0xA1
    const val ERROR_INVALID_PARAMETER = 0xA2
    const val ERROR_ACTION_FAILED = 0xA3
}
