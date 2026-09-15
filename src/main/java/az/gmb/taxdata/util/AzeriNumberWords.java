package az.gmb.taxdata.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class AzeriNumberWords {
    private AzeriNumberWords() {}
    private static final String[] ONES={"","bir","iki","üç","dörd","beş","altı","yeddi","səkkiz","doqquz"};
    private static final String[] TENS={"","on","iyirmi","otuz","qırx","əlli","altmış","yetmiş","səksən","doxsan"};
    private static final String[] SCALE={"","min","milyon","milyard","trilyon"};

    public static String money(BigDecimal value){
        BigDecimal v=(value==null?BigDecimal.ZERO:value).setScale(2,RoundingMode.HALF_UP);
        long manat=v.longValue();
        int qepik=v.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue();
        return integer(manat)+" manat "+(qepik==0?"sıfır":integer(qepik))+" qəpik";
    }
    public static String integer(long n){
        if(n==0)return "sıfır";
        if(n<0)return "mənfi "+integer(-n);
        StringBuilder out=new StringBuilder(); int scale=0;
        while(n>0){int group=(int)(n%1000); if(group>0){String g=group(group); if(scale==1 && group==1) g=""; String part=(g+(g.isBlank()?"":" ")+SCALE[scale]).trim(); out.insert(0,part+(out.length()>0?" ":""));} n/=1000; scale++;}
        return out.toString().trim();
    }
    private static String group(int n){
        StringBuilder s=new StringBuilder(); int h=n/100; int r=n%100;
        if(h>0){if(h>1)s.append(ONES[h]).append(' ');s.append("yüz");}
        int t=r/10,o=r%10; if(t>0){if(s.length()>0)s.append(' ');s.append(TENS[t]);} if(o>0){if(s.length()>0)s.append(' ');s.append(ONES[o]);}
        return s.toString();
    }
}
