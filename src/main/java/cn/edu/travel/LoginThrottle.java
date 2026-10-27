package cn.edu.travel;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/** Process-local, bounded login failure windows. Callers synchronize on this instance across verification. */
@Component
final class LoginThrottle {
    static final long WINDOW=600_000, COOLDOWN=60_000;
    private final Clock clock;
    private final int capacity;
    private final Map<String,Entry> sources=new HashMap<>();
    private final Map<Pair,Entry> pairs=new HashMap<>();
    private record Pair(String source,String username) {}
    private static final class Entry { final ArrayDeque<Long> failures=new ArrayDeque<>(); long until; }
    LoginThrottle(){this(Clock.systemUTC(),10000);}
    LoginThrottle(Clock clock,int capacity){this.clock=clock;this.capacity=capacity;}
    private void clean(Entry e,long now){while(!e.failures.isEmpty() && e.failures.peekFirst()<=now-WINDOW)e.failures.removeFirst();if(e.until<=now)e.until=0;}
    private boolean empty(Entry e){return e.until==0 && e.failures.isEmpty();}
    private void purge(long now){sources.values().removeIf(e->{clean(e,now);return empty(e);});pairs.values().removeIf(e->{clean(e,now);return empty(e);});}
    private void checkEntry(Entry e,long now,String scope){if(e!=null && e.until>now)throw new LoginCooldown((int)Math.max(1,(e.until-now+999)/1000),scope);}
    static String keyName(String name){StringBuilder out=new StringBuilder(name.length());for(char c:name.toCharArray())out.append(c>='A'&&c<='Z'?(char)(c+32):c);return out.toString();}
    void check(String source,String name){
        name=keyName(name);
        long now=clock.millis();purge(now);Pair key=new Pair(source,name);
        checkEntry(sources.get(source),now,"source");checkEntry(pairs.get(key),now,"pair");
        int needed=(sources.containsKey(source)?0:1)+(pairs.containsKey(key)?0:1);
        if(sources.size()+pairs.size()+needed>capacity)throw new ApiError(503,"登录服务繁忙，请稍后重试");
    }
    void failed(String source,String name){
        name=keyName(name);
        long now=clock.millis();Entry s=sources.computeIfAbsent(source,k->new Entry());Entry p=pairs.computeIfAbsent(new Pair(source,name),k->new Entry());
        s.failures.addLast(now);p.failures.addLast(now);
        if(s.failures.size()>=30){s.failures.clear();s.until=now+COOLDOWN;}
        if(p.failures.size()>=5){p.failures.clear();p.until=now+COOLDOWN;}
        checkEntry(s,now,"source");checkEntry(p,now,"pair");
    }
    void success(String source,String name){pairs.remove(new Pair(source,keyName(name)));}
}

final class LoginCooldown extends ApiError {
    final int seconds; final String scope;
    LoginCooldown(int seconds,String scope){super(429,"登录尝试过于频繁，请稍后再试");this.seconds=seconds;this.scope=scope;}
}
