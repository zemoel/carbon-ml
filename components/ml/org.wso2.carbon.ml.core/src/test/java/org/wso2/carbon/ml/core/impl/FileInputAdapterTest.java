/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.ml.core.impl;

import java.io.File;
import java.io.InputStream;
import java.net.URISyntaxException;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.ml.core.exceptions.MLInputAdapterException;
import org.wso2.carbon.ml.core.interfaces.MLInputAdapter;
import org.wso2.carbon.ml.core.impl.FileInputAdapter;

public class FileInputAdapterTest {
    @Test
    public void testReadDataset() throws URISyntaxException, MLInputAdapterException {
        MLInputAdapter inputAdapter = new FileInputAdapter();
        String filePrefix = "";
        String uriString = "src" + File.separator + "test" + File.separator + "resources" + File.separator
                + "fcSample.csv";
        String uri = filePrefix + uriString;
        InputStream in = null;
        // test a relative path
        try {
            in = inputAdapter.read(uri);
        } catch (Exception e) {
            Assert.assertEquals(e instanceof MLInputAdapterException, true);
        }

        // test a full path
        uri = filePrefix + System.getProperty("user.dir") + File.separator + uriString;
        in = inputAdapter.read(uri);
        Assert.assertNotNull(in);

        // test a uri without file:// prefix
        uri = System.getProperty("user.dir") + File.separator + uriString;
        in = inputAdapter.read(uri);
        Assert.assertNotNull(in);

        // file not found
        uri = System.getProperty("user.dir") + File.separator + uriString + ".csv";
        try {
            in = inputAdapter.read(uri);
        } catch (Exception e) {
            Assert.assertEquals(e instanceof MLInputAdapterException, true);
        }
    }
}
