/**
    Copyright (C) 2014  AIT / Austrian Institute of Technology
    http://www.ait.ac.at

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see http://www.gnu.org/licenses/agpl-3.0.html
 */
package at.ac.ait.ubicity.ubicity.flickrplugin.impl;

import at.ac.ait.ubicity.commons.AbstractCore;

import static at.ac.ait.ubicity.commons.AbstractCore.server;
import at.ac.ait.ubicity.commons.PluginContext;
import at.ac.ait.ubicity.commons.protocol.Answer;
import at.ac.ait.ubicity.commons.protocol.Command;
import at.ac.ait.ubicity.commons.protocol.Control;
import at.ac.ait.ubicity.commons.protocol.Medium;
import at.ac.ait.ubicity.commons.protocol.Terms;
import at.ac.ait.ubicity.core.Core;
import at.ac.ait.ubicity.ubicity.flickrplugin.FotoGrabber;

import com.flickr4java.flickr.Flickr;
import com.flickr4java.flickr.FlickrException;
import com.flickr4java.flickr.REST;
import com.flickr4java.flickr.photos.Photo;
import com.flickr4java.flickr.photos.PhotoList;
import com.flickr4java.flickr.photos.PhotosInterface;
import com.flickr4java.flickr.photos.SearchParameters;


import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;

import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.json.JSONObject;

/**
 *
 * @author jan van oort
 */
public class FotoGrabberImpl implements FotoGrabber {

    
    private Medium myMedium;
    
    private Map< String, TermHandler > handlers = new ConcurrentHashMap();
    
    private final static String name = "Flickr plugin for ubicity";
    
    private final AbstractCore core;
    
    private PluginContext context;
    
    private TransportClient esClient;

    private final String index  = "flickr";
    
    final static Logger logger = Logger.getLogger( FotoGrabberImpl.class.getName() );
    
    static  {
        logger.setLevel( Level.ALL );
    }
    
    
    public FotoGrabberImpl()    {
        core = Core.getInstance();
        myMedium = Medium.FLICKR;
        core.register( this );
        System.out.println( "[FLICKR]"+ " registered with ubicity core " );
        init();
        System.out.println( "[FLICKR]"+ " initiated " );
        Thread t = new Thread( this );
        t.setName( "execution context for " + name );
        t.start();        
    }
    
    
    @Override
    public Answer execute(Command _command) {
        System.out.println( "[FLICKR] received a Command :: " + _command.toRESTString() );
        //do some sanity checking upon the command
        if( ! _command.getMedia().get().contains( myMedium ) ) return Answer.FAIL;
        if( null == _command.getTerms() || null == _command.getTerms().get() ) return Answer.ERROR;
        
        //deal with the case we have a control to execute, and get it out of the way:
        if( ! ( null == _command.getControl() ) ) return doControlOperation( _command ) ? Answer.ACK : Answer.FAIL;
        
        //we have the right Medium in the command, and we have Terms: we can go down to business
        Terms __terms = _command.getTerms();
        TermHandler tH = new TermHandler( __terms, esClient );
        handlers.put( __terms.getType(), tH );
        Thread tTH = new Thread( tH );
        tH.setPriority( Thread.MAX_PRIORITY );
        tH.start();
        logger.info( "started TermHandler for type " + __terms.getType() );
        return Answer.ACK;
    }

    /**
     *
     */
    @Override
    public void mustStop() {
        Thread.currentThread().stop();
    }

    /**
     *
     * @return
     */
    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setContext(PluginContext _context) {
        context = _context;
    }

    @Override
    public PluginContext getContext() {
        return context;
    }

    
    
    @SuppressWarnings({"BroadCatchBlock", "TooBroadCatch", "SleepWhileInLoop"})
    @Override
    public void run() {
        while( true )   {
            try {
                Thread.sleep( 10 );
                for( TermHandler t: handlers.values() ) {
                    if( t.done ) {
                        t.stop();
                        handlers.remove( t.terms.getType() );
                        t = null;
                    }
                }
            }
            catch( InterruptedException _interrupt )    {
                Thread.interrupted();
            }
            catch( Exception | Error e )    {
                logger.severe( "Caught an exception while running : " + e.toString() );
            }
        }
    }

    
    private boolean doControlOperation( Command _command ) {
        //find the TermHandler we need to act upon
        StringBuilder sb = new StringBuilder();
        
        _command.getTerms().get().stream().forEach((t) -> {
            sb.append( t.getValue().toLowerCase() );
        });
        
        TermHandler tH = handlers.get( sb.toString() );
        if( null == tH ) return false;
        if( _command.getControl().equals( Control.PAUSE ) ) return tH.doPause();
        if( _command.getControl().equals( Control.STOP ) ) return tH.doStop();
        
        // if we land here, something has gone pretty wrong
        Logger.getAnonymousLogger( this.getClass().getName() ).warning( "could not determine which control to perform, or for which terms the control was meant. Here follows a representation of the Command received : " + _command.toString() );
        return false;
    }

    
    
    
    
    private final void init() {
        //instantiate an elasticsearch client
       Settings settings = ImmutableSettings.settingsBuilder().build();
        esClient = new TransportClient(settings).addTransportAddress(new InetSocketTransportAddress( server, 9300));
        try  {
            
            CreateIndexRequestBuilder createIndexRequestBuilder = esClient.admin().indices().prepareCreate( index );
            createIndexRequestBuilder.execute().actionGet();
        }
        catch( Throwable t )    {
            //do nothing, we may get an IndexAlreadyExistsException, but don't care about that, here and now
        }        
    }
}



final class TermHandler extends Thread  {

    
    final Terms terms;
    
    private final TransportClient esClient;
    
    boolean done = false;
    
    TermHandler( Terms _terms, TransportClient _esClient )     {
        terms = _terms;
        esClient = _esClient;
        
    }

    
    
    @Override
    public final void run()   {
        FotoGrabberImpl.logger.info( "[FLICKR] TermHander for " + terms.getType() + " :: RUN " );

        String key = "88e649a011244b9204a8821f2c9e63a2";
        String secret = "2cf8d4e7a07ff516";
        String srvr = "www.flickr.com";
        REST rest = new REST();
        rest.setHost( srvr );
        
        //initialized flickr object with key and REST
        Flickr flickr = new Flickr( key, secret, rest );
        Flickr.debugStream = false;
        
        //initialize SearchParameter object, which stores the search keyword(s)
        SearchParameters searchParams = new SearchParameters();
        searchParams.setSort( SearchParameters.RELEVANCE );
        
        //create tag keyword array
        int termCounter = 0;
        
        String[] tags = new String[ terms.termList.size() ];
        
        terms.termList.stream().forEach((_t) -> {
            tags[ termCounter ] = _t.getValue();
        });
        
        
        searchParams.setTags( tags );
        
        //initialize PhotosInterface object
        PhotosInterface photosInterface = flickr.getPhotosInterface();
        //execute search with given tags
        

        int __counter = 0;
        long _start, _lapse;
        _start = System.nanoTime();
        String[] __urls = new String[ 100 ];
        
        try {
            PhotoList<Photo> photoList = photosInterface.search( searchParams, 100 , 1 );
            _lapse = ( System.nanoTime() - _start  ) / 1000;
            System.out.println( "[FLICKR] * * * * *  searched flickr, got " + photoList.size() + " results in " + _lapse + " microseconds * * * * *  "  );

            for( Photo p: photoList )   {
                __urls[ __counter ] = p.getLargeUrl();
                __counter++;
            }
            index( __urls );
        }
        catch( FlickrException fe ) {
            done = true;
            throw new Error( fe );
        }
        done = true;
        return;
    }
    
    
    public final boolean doStop() {
        try {   
            stop();
            return true;
        }
        catch( Exception  | Error e )   {
            Logger.getLogger( this.getClass().getName() ).warning( "problem encountered while trying to stop " );
            return false;
        }
    }
    
    
    
    
   public final boolean doPause()    {
        try {
            this.suspend();
            return true;
        }
        catch( Exception | Error e )    {
            Logger.getLogger( this.getClass().getName() ).warning( "problem encountered while trying to pause " );
            return false;
        }
    }

   
   
   
   
    private void index(String[] __urls) {
       
        String __type = terms.getType();
        BulkRequestBuilder bulk = esClient.prepareBulk();
        
        for( String __url: __urls ) {
            Map< String, String > __rawJSON = new HashMap();
                    
            __rawJSON.put( "url", __url );
            JSONObject __o = new JSONObject( __rawJSON );
            System.out.println( __o.toString() );
            
            String __id = new StringBuilder().append( System.currentTimeMillis() ).append( System.nanoTime() ).toString();
            IndexRequest __req = new IndexRequest( "flickr", __type, __id );
            __req.source( __o.toString() );
            
            bulk.add( __req );
        }
        BulkResponse __response = bulk.execute().actionGet();
        if( __response.hasFailures() )  {
            System.out.println(  __response.buildFailureMessage() );
        }
        return;
    }
}
