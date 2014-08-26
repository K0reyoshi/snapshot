/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.snapshot.service.impl;

import java.io.File;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.batch.item.NonTransientResourceException;
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.UnexpectedInputException;
import org.springframework.util.CollectionUtils;

/**
 * @author Daniel Bernstein
 *         Date: Aug 22, 2014
 */
public class ContentPropertiesFileReaderTest {

    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception {
    }

    /**
     * @throws java.lang.Exception
     */
    @After
    public void tearDown() throws Exception {
    }

    /**
     * Test method for {@link org.duracloud.snapshot.service.impl.ContentPropertiesFileReader#read()}.
     * @throws Exception 
     * @throws NonTransientResourceException 
     * @throws ParseException 
     * @throws UnexpectedInputException 
     */
    @Test
    public void testRead() throws Exception {
        ContentPropertiesFileReader reader =
            new ContentPropertiesFileReader(new File(getClass().getResource("/content-properties.json")
                                                        .getFile()));
        ContentProperties props = null;
        int count = 0;
        while((props = reader.read()) !=null){
            verifyProps(props);
            count++;
        }
        
        Assert.assertEquals(2, count++);
    }

    /**
     * @param props
     */
    private void verifyProps(ContentProperties props) {
        Assert.assertNotNull(props.getContentId());
        Assert.assertTrue(!CollectionUtils.isEmpty(props.getProperties()));

    }

}
