UPDATE users
SET phone_number = CASE
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 15 AND TRIM(phone_number) LIKE '+2340%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 6)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 14 AND TRIM(phone_number) LIKE '+234%'
        THEN TRIM(phone_number)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 14 AND TRIM(phone_number) LIKE '2340%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 5)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 13 AND TRIM(phone_number) LIKE '234%'
        THEN '+' || TRIM(phone_number)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 12 AND TRIM(phone_number) LIKE '+0%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 3)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 11 AND TRIM(phone_number) LIKE '0%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 2)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 11 AND TRIM(phone_number) LIKE '+%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 2)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 10
        THEN '+234' || TRIM(phone_number)
    ELSE TRIM(phone_number)
END
WHERE phone_number IS NOT NULL AND TRIM(phone_number) <> '';

UPDATE orders
SET creator_phone_number = CASE
    WHEN CHAR_LENGTH(TRIM(creator_phone_number)) = 15 AND TRIM(creator_phone_number) LIKE '+2340%'
        THEN '+234' || SUBSTRING(TRIM(creator_phone_number), 6)
    WHEN CHAR_LENGTH(TRIM(creator_phone_number)) = 14 AND TRIM(creator_phone_number) LIKE '+234%'
        THEN TRIM(creator_phone_number)
    WHEN CHAR_LENGTH(TRIM(creator_phone_number)) = 14 AND TRIM(creator_phone_number) LIKE '2340%'
        THEN '+234' || SUBSTRING(TRIM(creator_phone_number), 5)
    WHEN CHAR_LENGTH(TRIM(creator_phone_number)) = 13 AND TRIM(creator_phone_number) LIKE '234%'
        THEN '+' || TRIM(creator_phone_number)
    WHEN CHAR_LENGTH(TRIM(creator_phone_number)) = 12 AND TRIM(creator_phone_number) LIKE '+0%'
        THEN '+234' || SUBSTRING(TRIM(creator_phone_number), 3)
    WHEN CHAR_LENGTH(TRIM(creator_phone_number)) = 11 AND TRIM(creator_phone_number) LIKE '0%'
        THEN '+234' || SUBSTRING(TRIM(creator_phone_number), 2)
    WHEN CHAR_LENGTH(TRIM(creator_phone_number)) = 11 AND TRIM(creator_phone_number) LIKE '+%'
        THEN '+234' || SUBSTRING(TRIM(creator_phone_number), 2)
    WHEN CHAR_LENGTH(TRIM(creator_phone_number)) = 10
        THEN '+234' || TRIM(creator_phone_number)
    ELSE TRIM(creator_phone_number)
END
WHERE creator_phone_number IS NOT NULL AND TRIM(creator_phone_number) <> '';

UPDATE order_counterparties
SET phone_number = CASE
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 15 AND TRIM(phone_number) LIKE '+2340%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 6)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 14 AND TRIM(phone_number) LIKE '+234%'
        THEN TRIM(phone_number)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 14 AND TRIM(phone_number) LIKE '2340%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 5)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 13 AND TRIM(phone_number) LIKE '234%'
        THEN '+' || TRIM(phone_number)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 12 AND TRIM(phone_number) LIKE '+0%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 3)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 11 AND TRIM(phone_number) LIKE '0%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 2)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 11 AND TRIM(phone_number) LIKE '+%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 2)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 10
        THEN '+234' || TRIM(phone_number)
    ELSE TRIM(phone_number)
END
WHERE phone_number IS NOT NULL AND TRIM(phone_number) <> '';

UPDATE otp_challenges
SET phone_number = CASE
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 15 AND TRIM(phone_number) LIKE '+2340%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 6)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 14 AND TRIM(phone_number) LIKE '+234%'
        THEN TRIM(phone_number)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 14 AND TRIM(phone_number) LIKE '2340%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 5)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 13 AND TRIM(phone_number) LIKE '234%'
        THEN '+' || TRIM(phone_number)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 12 AND TRIM(phone_number) LIKE '+0%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 3)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 11 AND TRIM(phone_number) LIKE '0%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 2)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 11 AND TRIM(phone_number) LIKE '+%'
        THEN '+234' || SUBSTRING(TRIM(phone_number), 2)
    WHEN CHAR_LENGTH(TRIM(phone_number)) = 10
        THEN '+234' || TRIM(phone_number)
    ELSE TRIM(phone_number)
END
WHERE phone_number IS NOT NULL AND TRIM(phone_number) <> '';
