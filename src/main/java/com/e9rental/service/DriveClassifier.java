package com.e9rental.service;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
@Service
public class DriveClassifier {
    public record Classification(String category,String reason) {}
    public Classification classify(String title,String content) {
        String text=(title+" "+content).toLowerCase(Locale.ROOT).replace('_',' ');
        String[][] rules={
            {"verification","kyc|passport|aadhaar|aadhar|pan card|identity proof|verification"},
            {"accounts","invoice|receipt|ledger|balance sheet|tax|accounting|payment|expense|financial"},
            {"sales","quotation|quote|proposal|purchase order|sales|pricing|rfq"},
            {"marketing","campaign|brochure|marketing|newsletter|promotion|advertisement"}
        };
        for (var rule:rules) if (Pattern.compile("\\b("+rule[1]+")\\b").matcher(text).find())
            return new Classification(rule[0],"Matched "+rule[0]+" keyword; deterministic precedence verification, accounts, sales, marketing, office");
        return new Classification("office","General office document; no higher-priority category keyword");
    }
}
