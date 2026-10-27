import cn.edu.travel.SearchText;
import java.util.*;

public class verify_search_text {
    static int count=0;
    static void check(boolean value){if(!value)throw new AssertionError("check "+(count+1));count++;}
    static String plain(List<Map<String,Object>> parts){return parts.stream().map(p->p.get("text").toString()).reduce("",String::concat);}
    static String marked(String text,String query){return SearchText.segments(text,SearchText.terms(query),0).stream().filter(p->Boolean.TRUE.equals(p.get("match"))).map(p->p.get("text").toString()).reduce("",String::concat);}
    public static void main(String[] args){
        check(SearchText.terms("南阳  汉画 南阳").equals(List.of("南阳","汉画")));
        check(SearchText.terms("ＡＢＣ abc").equals(List.of("abc")));
        check(SearchText.terms("%_\\\"()").isEmpty());
        check(SearchText.match(SearchText.terms("武侯祠")).equals("\"c6b66 c4faf c7960\""));
        check(SearchText.match(SearchText.terms("南阳 OR 武侯")).contains(" AND "));
        check(!SearchText.match(SearchText.terms("OR")).contains("OR"));
        check(SearchText.hits("南阳市汉画馆",List.of("汉画")));
        check(!SearchText.hits("汉。画",List.of("汉画")));
        check(SearchText.hits("全角ＡＢＣ",List.of("abc")));
        check(!SearchText.hits("abcd",List.of("abc")));
        check(marked("全角ＡＢＣ", "abc").equals("ＡＢＣ"));
        check(marked("OR or orange", "or").equals("ORor"));
        check(marked("Cafe\u0301", "café").equals("Cafe\u0301"));
        check(marked("南阳市汉画馆", "南阳 汉画").equals("南阳汉画"));
        check(marked("汉画馆", "汉画 画馆").equals("汉画馆"));
        check(plain(SearchText.segments("<img onerror=alert(1)>青石",List.of("青石"),0)).equals("<img onerror=alert(1)>青石"));
        String longText="\uD83D\uDE00".repeat(130)+"武侯祠"+"\uD83D\uDE00".repeat(130);
        var parts=SearchText.segments(longText,List.of("武侯祠"),120);String output=plain(parts);
        check(output.startsWith("…")&&output.endsWith("…")&&output.contains("武侯祠"));
        check(output.codePointCount(0,output.length())<=122);
        check(output.codePoints().noneMatch(p->p>=0xD800&&p<=0xDFFF));
        check(SearchText.segments("",List.of("空"),120).isEmpty());
        boolean rejected=false;try{SearchText.terms("a b c d e f g h i j k l m");}catch(RuntimeException e){rejected=true;}check(rejected);
        Random random=new Random(20261026);
        for(int i=0;i<200;i++){
            String text="前".repeat(random.nextInt(200))+"汉画馆"+"后".repeat(random.nextInt(200));
            var excerpt=SearchText.segments(text,List.of("汉画"),120);
            check(excerpt.stream().anyMatch(p->Boolean.TRUE.equals(p.get("match"))&&p.get("text").equals("汉画")));
            check(plain(excerpt).codePointCount(0,plain(excerpt).length())<=122);
        }
        System.out.println("PASS "+count+" search text assertions");
    }
}
