package com.smartinbox.processor.job;

import com.smartinbox.processor.entity.JobApplication;
import com.smartinbox.processor.entity.JobMailSuggestion;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative identity matching: a shared job title never establishes a company. */
final class JobApplicationMatcher {
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "inc", "incorporated", "corp", "corporation", "ltd", "limited", "llc", "plc");
    private static final Set<String> UNKNOWN_COMPANIES = Set.of(
            "", "unknown", "unknown company", "company unknown", "unspecified", "not specified",
            "not available", "not provided", "unidentified", "n a", "na", "none", "null",
            "未知", "未知公司", "公司未知", "不明", "未识别", "未识别公司", "未提供", "未提供公司");
    private static final Set<String> GENERIC_COMPANY_WORDS = Set.of(
            "the", "a", "an", "and", "of", "company", "group", "bank", "global", "international",
            "technology", "technologies", "tech", "software", "solutions", "services", "business",
            "recruitment", "recruiting", "careers", "career", "jobs", "job", "intern", "internship",
            "graduate", "developer", "engineer", "engineering", "unknown", "unspecified",
            "inc", "incorporated", "corp", "corporation", "ltd", "limited", "llc", "plc");

    private JobApplicationMatcher() {}

    static String bestMatch(JobMailSuggestion suggestion, List<JobApplication> applications) {
        if (!knownCompany(suggestion.getCompany())) return null;
        List<JobApplication> companyMatches = applications.stream()
                .filter(application -> sameCompany(suggestion.getCompany(), application.getCompany())).toList();
        if (companyMatches.size() == 1) return companyMatches.get(0).getId();
        String role = normalized(suggestion.getRole());
        if (role.isBlank()) return null;
        List<JobApplication> roleMatches = companyMatches.stream()
                .filter(application -> role.equals(normalized(application.getRole()))).toList();
        return roleMatches.size() == 1 ? roleMatches.get(0).getId() : null;
    }

    static boolean knownCompany(String company) {
        return !UNKNOWN_COMPANIES.contains(companyKey(company));
    }

    static boolean sameCompany(String first, String second) {
        if (!knownCompany(first) || !knownCompany(second)) return false;
        String a = companyKey(first), b = companyKey(second);
        if (a.equals(b)) return true;
        // Users may include a program or region after a brand, e.g. "RBC Amplify 2027".
        // Only a complete leading brand token qualifies; RBCapital and an interior RBC do not.
        return brandPrefix(a, b) || brandPrefix(b, a);
    }

    private static boolean brandPrefix(String brand, String longer) {
        if (!longer.startsWith(brand + " ")) return false;
        return java.util.Arrays.stream(brand.split(" ")).anyMatch(word ->
                !GENERIC_COMPANY_WORDS.contains(word) && word.codePoints().filter(Character::isLetter).count() >= 3);
    }

    private static String companyKey(String company) {
        // Dots in names such as I.B.M. and Inc. do not change the company's identity.
        String value = normalized(company == null ? "" : company.replace(".", ""));
        String[] words = value.split(" ");
        int end = words.length;
        while (end > 1 && LEGAL_SUFFIXES.contains(words[end - 1])) end--;
        return String.join(" ", java.util.Arrays.copyOf(words, end));
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
