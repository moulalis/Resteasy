package org.jboss.resteasy.client.jaxrs.internal;

import org.jboss.resteasy.client.jaxrs.i18n.Messages;
import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.core.ProvidersContextRetainer;
import org.jboss.resteasy.core.interception.ClientReaderInterceptorContext;
import org.jboss.resteasy.specimpl.AbstractBuiltResponse;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.jboss.resteasy.spi.HeaderValueProcessor;
import org.jboss.resteasy.spi.MarshalledEntity;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.jboss.resteasy.util.HttpHeaderNames;
import org.jboss.resteasy.util.InputStreamToByteArray;
import org.jboss.resteasy.util.ReadFromStream;
import org.jboss.resteasy.util.Types;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Providers;
import javax.ws.rs.ext.ReaderInterceptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public abstract class ClientResponse extends BuiltResponse
{
   // One thing to note, I don't cache header objects because I was too lazy to proxy the headers multivalued map
   protected Map<String, Object> properties;
   protected ClientConfiguration configuration;

   protected ClientResponse(final ClientConfiguration configuration)
   {
      setClientConfiguration(configuration);
   }

   @SuppressWarnings({"rawtypes", "unchecked"})
   public void setHeaders(MultivaluedMap<String, String> headers)
   {
      this.metadata = new Headers<Object>();

      List list = new ArrayList<>();
      list.add("test");

      headers.put("PPropTEst",list);


      this.metadata.putAll((Map) headers);
   }

   public void setProperties(Map<String, Object> properties)
   {
      this.properties = properties;
   }

   public Map<String, Object> getProperties()
   {
      return properties;
   }

   public void setClientConfiguration(ClientConfiguration configuration)
   {
      this.configuration = configuration;
      this.processor = configuration;
   }

   @Override
   public synchronized Object getEntity()
   {
      abortIfClosed();
      Object entity = super.getEntity();
      if (entity != null)
      {
         return checkEntityReadAsInputStreamFullyConsumed(entity);
      }
      return checkEntityReadAsInputStreamFullyConsumed(getEntityStream());
   }

   //Check if the entity was previously fully consumed
   private <T> T checkEntityReadAsInputStreamFullyConsumed(T entity)
   {
      if (bufferedEntity == null && entity instanceof InputStream && streamFullyRead)
      {
         throw new IllegalStateException();
      }
      return entity;
   }

   @Override
   public Class<?> getEntityClass()
   {
      Class<?> classs = super.getEntityClass();
      if (classs != null)
      {
         return classs;
      }
      Object entity = null;
      try
      {
         entity = getEntity();
      }
      catch (Exception e)
      {
      }
      return entity != null ? entity.getClass() : null;
   }

   @Override
   public boolean hasEntity()
   {
      abortIfClosed();
      return getInputStream() != null && (entity != null || getMediaType() != null);
   }

   /**
    * In case of an InputStream or Reader and a invocation that returns no Response object, we need to make
    * sure the GC does not close the returned InputStream or Reader
    */
   public void noReleaseConnection()
   {

      isClosed = true;
   }

   @Override
   public void close()
   {
      if (isClosed()) return;
      try {
         isClosed = true;
         releaseConnection();
      }
      catch (Exception e) {
         throw new ProcessingException(e);
      }
   }

   @Override
   // This method is synchronized to protect against premature calling of finalize by the GC
   protected synchronized void finalize() throws Throwable
   {
      if (isClosed()) return;
      try {
         close();
      }
      catch (Exception ignored) {
      }
   }

   @Override
   protected HeaderValueProcessor getHeaderValueProcessor()
   {
      return configuration;
   }

   protected InputStream getEntityStream()
   {
      if (bufferedEntity != null) return new ByteArrayInputStream(bufferedEntity);

      if (isClosed()) throw new ProcessingException(Messages.MESSAGES.streamIsClosed());
      InputStream is = getInputStream();
      return is != null ? new AbstractBuiltResponse.InputStreamWrapper<ClientResponse>(is, this) : null;
   }

   // Method is defined here because the "protected" abstract declaration
   // in AbstractBuiltResponse is not accessible to classes in this module.
   // Making the method "public" causes different errors.
   protected abstract void setInputStream(InputStream is);

   // this is synchronized in conjunction with finalize to protect against premature finalize called by the GC
   @Override
   protected synchronized <T> Object readFrom(Class<T> type, Type genericType,
                                    MediaType media, Annotation[] annotations)
   {
      Type useGeneric = genericType == null ? type : genericType;
      Class<?> useType = type;
      media = media == null ? MediaType.WILDCARD_TYPE : media;
      annotations = annotations == null ? this.annotations : annotations;
      boolean isMarshalledEntity = false;
      if (type.equals(MarshalledEntity.class))
      {
         isMarshalledEntity = true;
         ParameterizedType param = (ParameterizedType) useGeneric;
         useGeneric = param.getActualTypeArguments()[0];
         useType = Types.getRawType(useGeneric);
      }


      Providers current = ResteasyProviderFactory.getContextData(Providers.class);
      ResteasyProviderFactory.pushContext(Providers.class, configuration);
      Object obj = null;
      try
      {
         InputStream is = getEntityStream();
         if (is == null)
         {
            throw new IllegalStateException(Messages.MESSAGES.inputStreamWasEmpty());
         }
         if (isMarshalledEntity)
         {
            is = new InputStreamToByteArray(is);

         }

         ReaderInterceptor[] readerInterceptors = configuration.getReaderInterceptors(null, null);

          final Object finalObj = new ClientReaderInterceptorContext(readerInterceptors, configuration.getProviderFactory(), useType,
                 useGeneric, annotations, media, getStringHeaders(), is, properties)
                 .proceed();

         obj = finalObj;

         Map<String,String> mapC = new HashMap<>();
         if(properties.containsKey("MP_CLIENT_CONTAINER_HEADERS")){
            for (Map.Entry<String,Object> entry : properties.entrySet()){
               if(entry.getKey().equals("MP_CLIENT_CONTAINER_HEADERS")){

                  Map<String,String> map = (Map<String, String>) entry.getValue();
                  Map<String,Map<String,String>> imap = (Map<String, Map<String,String>>) finalObj;
                  Map<String,String> fmap = imap.get("IncomingRequestHeaders");
                  Map<String,String> upmap = new HashMap<>(fmap);
                  map.entrySet().stream().filter(x -> !fmap.containsKey(x.getKey())).forEach(x -> mapC.put(x.getKey(), x.getValue()));
                  upmap.putAll(mapC);

               }
            }

         }


         if (isMarshalledEntity)
         {
            InputStreamToByteArray isba = (InputStreamToByteArray) is;
            final byte[] bytes = isba.toByteArray();
            return new MarshalledEntity<Object>()
            {
               @Override
               public byte[] getMarshalledBytes()
               {
                  return bytes;
               }

               @Override
               public Object getEntity()
               {
                  return finalObj;
               }
            };
         }
         else
         {
            return finalObj;
         }

      }
      catch (ProcessingException pe)
      {
         throw pe;
      }
      catch (Exception ex)
      {
         throw new ProcessingException(ex);
      }
      finally
      {
         ResteasyProviderFactory.popContextData(Providers.class);
         if (current != null) ResteasyProviderFactory.pushContext(Providers.class, current);
         if (obj instanceof ProvidersContextRetainer)
         {
            ((ProvidersContextRetainer) obj).setProviders(configuration);
         }
      }
   }

   @Override
   public boolean bufferEntity()
   {
      abortIfClosed();
      if (bufferedEntity != null) return true;
      if (streamRead) return false;
      if (metadata.getFirst(HttpHeaderNames.CONTENT_TYPE) == null) return false;
      InputStream is = getInputStream();
      if (is == null) return false;
      try
      {
         bufferedEntity = ReadFromStream.readFromStream(1024, is);
      }
      catch (IOException e)
      {
         throw new ProcessingException(e);
      }
      finally
      {
         try {
            releaseConnection();
         }
         catch (IOException e) {
            throw new ProcessingException(e);
         }
      }
      return true;
   }

   @Override
   public void abortIfClosed()
   {
      if (bufferedEntity == null) super.abortIfClosed();
   }

}
