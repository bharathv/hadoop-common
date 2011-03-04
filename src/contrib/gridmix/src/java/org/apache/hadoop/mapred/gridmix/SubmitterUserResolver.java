/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred.gridmix;

import java.io.IOException;
import java.net.URI;
import javax.security.auth.login.LoginException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UnixUserGroupInformation;

/**
 * Resolves all UGIs to the submitting user.
 */
public class SubmitterUserResolver extends UserResolver {

  private UserGroupInformation ugi = null;

  public SubmitterUserResolver() { }

  public synchronized boolean setTargetUsers(URI userdesc, Configuration conf)
      throws IOException {
    try {
      ugi = UnixUserGroupInformation.login(conf, false);
    } catch (LoginException e) {
      throw new IOException("Failed to get submitter UGI", e);
    }
    return false;
  }

  public synchronized UserGroupInformation getTargetUgi(
      UserGroupInformation ugi) {
    return this.ugi;
  }

}