package cn.edu.travel;

import java.security.*;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Passwords {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final int ITERATIONS=210000;
    static String hash(String password) {
        byte[] salt=new byte[16]; RANDOM.nextBytes(salt);
        return ITERATIONS+":"+Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(password,salt,ITERATIONS));
    }
    static boolean matches(String password,String encoded) {
        try {
            String[] fields=encoded.split(":");
            return MessageDigest.isEqual(Base64.getDecoder().decode(fields[2]),derive(password,Base64.getDecoder().decode(fields[1]),Integer.parseInt(fields[0])));
        }catch(Exception e){return false;}
    }
    private static byte[] derive(String password,byte[] salt,int iterations) {
        try {
            PBEKeySpec key=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
            try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();}
            finally{key.clearPassword();}
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    static String token() {
        byte[] value=new byte[24]; RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    static void validate(String password) {
        if(password.length()<8 || password.length()>128) throw new ApiError(400,"密码请使用 8～128 个字符");
    }
}
