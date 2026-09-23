package com.fnph.telepsychiatric.clinical;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Generates human-readable investigation issue numbers.
 *
 * Format:
 *   INV-YYYYMMDD-XXXXXXXX
 *

 */
@Service
public class InvestigationNumberService {

    public String next() {
        String date = LocalDate.now()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase();

        return "INV-" + date + "-" + suffix;
    }
}

