package com.e9rental;

import com.e9rental.connector.GoogleTransport;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.Set;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.beans.factory.support.*;
import org.springframework.http.client.*;
import org.springframework.web.client.RestClient;

/** TEST CLASSPATH ONLY. Production URLs stay fixed; all test transport is forced onto loopback. */
public final class SyntheticPipelineApplication {
    static ClientHttpRequestFactory loopbackFactory(int port) {
        if(port<1 || port>65535)throw new IllegalArgumentException();
        var delegate=new SimpleClientHttpRequestFactory();delegate.setProxy(Proxy.NO_PROXY);
        delegate.setConnectTimeout(3000);delegate.setReadTimeout(3000);
        return (uri,method)->{
            if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null ||
                !Set.of("gmail.googleapis.com","www.googleapis.com","oauth2.googleapis.com").contains(uri.getHost()))
                throw new IOException("Synthetic harness refuses any unapproved destination");
            URI local=URI.create("http://127.0.0.1:"+port+uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
            return delegate.createRequest(local,method);
        };
    }
    public static void main(String[] args)throws Exception {
        int port=Integer.parseInt(System.getProperty("synthetic.provider.port"));
        var transport=new GoogleTransport(RestClient.builder().requestFactory(loopbackFactory(port)).build());
        var app=new SpringApplicationBuilder(ConnectorApplication.class).initializers(context->{
            var bean=new RootBeanDefinition(GoogleTransport.class);bean.setInstanceSupplier(()->transport);bean.setPrimary(true);
            ((BeanDefinitionRegistry)context).registerBeanDefinition("m2SyntheticTransport",bean);
        }).run(args);
        if(app.getBean(GoogleTransport.class)!=transport){app.close();throw new IllegalStateException("Test transport not selected");}
        int backend=((WebServerApplicationContext)app).getWebServer().getPort();
        Files.writeString(Path.of(System.getProperty("synthetic.ready.file")),Integer.toString(backend));
    }
}
