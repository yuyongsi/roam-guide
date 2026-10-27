package cn.edu.travel;

import java.text.Normalizer;
import java.text.BreakIterator;
import java.util.*;
import java.util.regex.*;

/** 无词典的中文字符短语索引；索引与查询使用相同的规范化规则。 */
public final class SearchText {
    private SearchText() {}
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+");
    public static String normalize(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    public static List<String> terms(String query) {
        var out=new LinkedHashSet<String>();
        Matcher matcher=WORD.matcher(normalize(query));
        while(matcher.find())out.add(matcher.group());
        if(out.size()>12)throw new ApiError(400,"最多组合 12 个关键词，请缩短搜索词");
        return List.copyOf(out);
    }
    // Hex 编码区分内容词与边界标记，避免把 FTS 运算符当作查询语法。
    private static String encoded(String text) {
        StringBuilder out=new StringBuilder("w");text.codePoints().forEach(c->out.append(Integer.toHexString(c)).append('x'));return out.toString();
    }
    private static String run(String text) {
        var tokens=new ArrayList<String>();StringBuilder western=new StringBuilder();
        text.codePoints().forEach(c->{
            if(Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN){
                if(!western.isEmpty()){tokens.add(encoded(western.toString()));western.setLength(0);}
                tokens.add("c"+Integer.toHexString(c));
            }else western.appendCodePoint(c);
        });
        if(!western.isEmpty())tokens.add(encoded(western.toString()));
        return String.join(" ",tokens);
    }
    public static String index(String text) {
        Matcher matcher=WORD.matcher(normalize(text));var out=new ArrayList<String>();
        while(matcher.find())out.add(run(matcher.group()));
        // 保留标点和段落的边界，中文短语不能跨过句号或段落拼接。
        return String.join(" zboundary ",out);
    }
    public static String match(List<String> terms) {
        return terms.stream().map(t->"\""+run(t)+"\"").reduce((a,b)->a+" AND "+b).orElse("");
    }
    public static boolean hits(String text,List<String> terms) {
        String indexed=" "+index(text)+" ";return terms.stream().anyMatch(t->indexed.contains(" "+run(t)+" "));
    }
    private static boolean western(int point){return Character.isLetterOrDigit(point)&&Character.UnicodeScript.of(point)!=Character.UnicodeScript.HAN;}
    /** 返回纯文本片段及标记，前端负责转义，接口永不返回可执行 HTML。 */
    public static List<Map<String,Object>> segments(String text,List<String> terms,int limit) {
        StringBuilder normalized=new StringBuilder();var starts=new ArrayList<Integer>();var ends=new ArrayList<Integer>();
        BreakIterator clusters=BreakIterator.getCharacterInstance(Locale.ROOT);clusters.setText(text);
        for(int i=clusters.first(),end=clusters.next();end!=BreakIterator.DONE;i=end,end=clusters.next()){
            String part=normalize(text.substring(i,end));normalized.append(part);for(int j=0;j<part.length();j++){starts.add(i);ends.add(end);}}
        String norm=normalized.toString();var ranges=new ArrayList<int[]>();
        for(String term:terms){int from=0,pos;while((pos=norm.indexOf(term,from))>=0){
            int finish=pos+term.length();
            boolean left=western(term.codePointAt(0))&&pos>0&&western(norm.codePointBefore(pos));
            boolean right=western(term.codePointBefore(term.length()))&&finish<norm.length()&&western(norm.codePointAt(finish));
            if(!left&&!right)ranges.add(new int[]{starts.get(pos),ends.get(finish-1)});from=pos+Math.max(1,term.length());}}
        ranges.sort(Comparator.comparingInt(a->a[0]));
        var merged=new ArrayList<int[]>();for(int[] r:ranges){if(!merged.isEmpty()&&r[0]<=merged.get(merged.size()-1)[1])merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],r[1]);else merged.add(r.clone());}
        int begin=0,end=text.length();
        if(limit>0&&text.codePointCount(0,text.length())>limit){
            int focus=merged.isEmpty()?0:merged.get(0)[0];int before=text.codePointCount(0,focus);
            begin=text.offsetByCodePoints(0,Math.max(0,before-25));
            end=text.offsetByCodePoints(begin,Math.min(limit,text.codePointCount(begin,text.length())));
        }
        var out=new ArrayList<Map<String,Object>>();if(begin>0)out.add(Map.of("text","…","match",false));int cursor=begin;
        for(int[] r:merged){int a=Math.max(begin,r[0]),b=Math.min(end,r[1]);if(a>=b)continue;
            if(a>cursor)out.add(Map.of("text",text.substring(cursor,a),"match",false));out.add(Map.of("text",text.substring(a,b),"match",true));cursor=b;}
        if(cursor<end)out.add(Map.of("text",text.substring(cursor,end),"match",false));
        if(end<text.length())out.add(Map.of("text","…","match",false));return out;
    }
}
