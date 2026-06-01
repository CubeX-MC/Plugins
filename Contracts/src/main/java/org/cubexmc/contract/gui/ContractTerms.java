package org.cubexmc.contract.gui;

final class ContractTerms {
    private ContractTerms() {
    }

    static String preview(String description) {
        String clean = description == null ? "" : description.strip();
        if (clean.isEmpty()) {
            return "未填写";
        }
        clean = clean.replaceAll("\\R+", " / ");
        return clean.length() <= 48 ? clean : clean.substring(0, 45) + "...";
    }
}
