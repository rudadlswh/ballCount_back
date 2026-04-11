package com.kbo.crawlerapi.support;

import java.util.Map;

public final class TeamCatalog {

    private static final Map<String, TeamDefinition> PROVIDER_NAME_MAP = Map.ofEntries(
            Map.entry("LG", new TeamDefinition("lg", "LG Twins", "LG", "LG Twins", null, "LG")),
            Map.entry("SSG", new TeamDefinition("ssg", "SSG Landers", "SSG", "SSG Landers", null, "SSG")),
            Map.entry("KIA", new TeamDefinition("kia", "KIA Tigers", "KIA", "KIA Tigers", null, "KIA")),
            Map.entry("두산", new TeamDefinition("doosan", "Doosan Bears", "Doosan", "Doosan Bears", null, "DOO")),
            Map.entry("삼성", new TeamDefinition("samsung", "Samsung Lions", "Samsung", "Samsung Lions", null, "SAM")),
            Map.entry("롯데", new TeamDefinition("lotte", "Lotte Giants", "Lotte", "Lotte Giants", null, "LOT")),
            Map.entry("NC", new TeamDefinition("nc", "NC Dinos", "NC", "NC Dinos", null, "NC")),
            Map.entry("키움", new TeamDefinition("kiwoom", "Kiwoom Heroes", "Kiwoom", "Kiwoom Heroes", null, "KIW")),
            Map.entry("KT", new TeamDefinition("kt", "KT Wiz", "KT", "KT Wiz", null, "KT")),
            Map.entry("한화", new TeamDefinition("hanwha", "Hanwha Eagles", "Hanwha", "Hanwha Eagles", null, "HAN"))
    );

    private TeamCatalog() {
    }

    public static TeamDefinition fromProviderName(String providerName) {
        TeamDefinition teamDefinition = PROVIDER_NAME_MAP.get(providerName);
        if (teamDefinition == null) {
            throw new IllegalArgumentException("Unsupported provider team name: " + providerName);
        }
        return teamDefinition;
    }

    public record TeamDefinition(
            String teamCode,
            String name,
            String shortName,
            String englishName,
            String logoUrl,
            String publicCode
    ) {
    }
}
