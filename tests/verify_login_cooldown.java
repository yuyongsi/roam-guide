package cn.edu.travel;
import java.time.*;
import java.util.concurrent.*;

class verify_login_cooldown {
 static class FakeClock extends Clock { long now=1_000_000; public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return Instant.ofEpochMilli(now);} void advance(long n){now+=n;} }
 static void ok(boolean v){if(!v)throw new AssertionError();}
 static LoginCooldown blocked(Runnable r){try{r.run();throw new AssertionError("expected cooldown");}catch(LoginCooldown e){return e;}}
 static void fail(LoginThrottle t,String source,String name){synchronized(t){t.check(source,name);t.failed(source,name);}}
 public static void main(String[] args)throws Exception {
  FakeClock c=new FakeClock();LoginThrottle t=new LoginThrottle(c,10000);
  for(int i=0;i<4;i++)fail(t,"a","Alice");ok(blocked(()->fail(t,"a","ALICE")).seconds==60);
  c.advance(20000);ok(blocked(()->t.check("a","alice")).seconds==40);c.advance(40000);t.check("a","alice");
  for(int i=0;i<4;i++)fail(t,"a","alice");t.success("a","ALICE");for(int i=0;i<4;i++)fail(t,"a","alice");
  t.check("b","alice");c.advance(600001);t.check("a","alice");
  final LoginThrottle sourceTest=new LoginThrottle(c,10000);
  for(int i=0;i<29;i++)fail(sourceTest,"same","n"+i);sourceTest.success("same","n1");ok(blocked(()->fail(sourceTest,"same","n29")).scope.equals("source"));sourceTest.check("other","n29");
  LoginThrottle boundary=new LoginThrottle(c,1000);for(int i=0;i<4;i++)fail(boundary,"a","x");c.advance(599999);boundary.check("a","x");c.advance(1);boundary.failed("a","x");boundary.check("a","x");
  LoginThrottle boundarySource=new LoginThrottle(c,1000);for(int i=0;i<29;i++)fail(boundarySource,"a","n"+i);c.advance(599999);boundarySource.check("a","last");c.advance(1);boundarySource.failed("a","last");boundarySource.check("a","last");
  LoginThrottle small=new LoginThrottle(c,2);fail(small,"a","x");try{small.check("b","y");throw new AssertionError();}catch(ApiError e){ok(e.status==503);}for(int i=0;i<3;i++)fail(small,"a","x");blocked(()->fail(small,"a","x"));c.advance(600001);small.check("b","y");
  LoginThrottle concurrent=new LoginThrottle(c,1000);ExecutorService pool=Executors.newFixedThreadPool(16);CountDownLatch gate=new CountDownLatch(1);java.util.List<Future<Integer>> futures=new java.util.ArrayList<>();
  for(int i=0;i<40;i++)futures.add(pool.submit(()->{gate.await();try{fail(concurrent,"src","user");return 0;}catch(LoginCooldown e){return 1;}}));gate.countDown();int rejected=0;for(var f:futures)rejected+=f.get();pool.shutdown();ok(rejected==36);
  ok(!LoginThrottle.keyName("Ä").equals(LoginThrottle.keyName("ä")));System.out.println("PASS login cooldown core");
 }
}

