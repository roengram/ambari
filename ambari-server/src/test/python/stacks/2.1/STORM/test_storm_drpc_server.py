#!/usr/bin/env python

'''
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
'''

from mock.mock import MagicMock, call, patch
from stacks.utils.RMFTestCase import *
import  resource_management.core.source
from test_storm_base import TestStormBase

class TestStormDrpcServer(TestStormBase):

  def test_configure_default(self):
    self.executeScript("2.1/services/STORM/package/scripts/drpc_server.py",
                       classname = "DrpcServer",
                       command = "configure",
                       config_file="default.json"
    )

    self.assert_configure_default()
    self.assertNoMoreResources()

  def test_start_default(self):

    self.executeScript("2.1/services/STORM/package/scripts/drpc_server.py",
                       classname = "DrpcServer",
                       command = "start",
                       config_file="default.json"
    )
    self.assert_configure_default()

    self.assertResourceCalled('Execute', 'env JAVA_HOME=/usr/jdk64/jdk1.7.0_45 PATH=$PATH:/usr/jdk64/jdk1.7.0_45/bin storm drpc > /var/log/storm/drpc.out 2>&1',
      wait_for_finish = False,
      not_if = 'ls /var/run/storm/drpc.pid >/dev/null 2>&1 && ps `cat /var/run/storm/drpc.pid` >/dev/null 2>&1',
      path = ['/usr/bin'],
      user = 'storm',
    )

    self.assertResourceCalled('Execute', 'pgrep -f "^java.+backtype.storm.daemon.drpc$" && pgrep -f "^java.+backtype.storm.daemon.drpc$" > /var/run/storm/drpc.pid',
      logoutput = True,
      tries = 6,
      user = 'storm',
      path = ['/usr/bin'],
      try_sleep = 10,
    )

    self.assertNoMoreResources()

  def test_stop_default(self):
    self.executeScript("2.1/services/STORM/package/scripts/drpc_server.py",
                       classname = "DrpcServer",
                       command = "stop",
                       config_file="default.json"
    )
    self.assertResourceCalled('Execute', 'kill `cat /var/run/storm/drpc.pid` >/dev/null 2>&1',
                              not_if = '! (ls /var/run/storm/drpc.pid >/dev/null 2>&1 && ps `cat /var/run/storm/drpc.pid` >/dev/null 2>&1)'
    )
    self.assertResourceCalled('Execute', 'kill -9 `cat /var/run/storm/drpc.pid` >/dev/null 2>&1',
                              not_if = 'sleep 2; ! (ls /var/run/storm/drpc.pid >/dev/null 2>&1 && ps `cat /var/run/storm/drpc.pid` >/dev/null 2>&1) || sleep 20; ! (ls /var/run/storm/drpc.pid >/dev/null 2>&1 && ps `cat /var/run/storm/drpc.pid` >/dev/null 2>&1)',
                              ignore_failures=True
    )
    self.assertResourceCalled('Execute', 'rm -f /var/run/storm/drpc.pid')
    self.assertNoMoreResources()

  def test_configure_secured(self):
    self.executeScript("2.1/services/STORM/package/scripts/drpc_server.py",
                       classname = "DrpcServer",
                       command = "configure",
                       config_file="secured.json"
    )
    self.assert_configure_secured()
    self.assertNoMoreResources()

  def test_start_secured(self):
    self.executeScript("2.1/services/STORM/package/scripts/drpc_server.py",
                       classname = "DrpcServer",
                       command = "start",
                       config_file="secured.json"
    )

    self.assert_configure_secured()

    self.assertResourceCalled('Execute', 'env JAVA_HOME=/usr/jdk64/jdk1.7.0_45 PATH=$PATH:/usr/jdk64/jdk1.7.0_45/bin storm drpc > /var/log/storm/drpc.out 2>&1',
      wait_for_finish = False,
      not_if = 'ls /var/run/storm/drpc.pid >/dev/null 2>&1 && ps `cat /var/run/storm/drpc.pid` >/dev/null 2>&1',
      path = ['/usr/bin'],
      user = 'storm',
    )

    self.assertResourceCalled('Execute', 'pgrep -f "^java.+backtype.storm.daemon.drpc$" && pgrep -f "^java.+backtype.storm.daemon.drpc$" > /var/run/storm/drpc.pid',
      logoutput = True,
      tries = 6,
      user = 'storm',
      path = ['/usr/bin'],
      try_sleep = 10,
    )

    self.assertNoMoreResources()

  def test_stop_secured(self):
    self.executeScript("2.1/services/STORM/package/scripts/drpc_server.py",
                       classname = "DrpcServer",
                       command = "stop",
                       config_file="secured.json"
    )
    self.assertResourceCalled('Execute', 'kill `cat /var/run/storm/drpc.pid` >/dev/null 2>&1',
                              not_if = '! (ls /var/run/storm/drpc.pid >/dev/null 2>&1 && ps `cat /var/run/storm/drpc.pid` >/dev/null 2>&1)'
    )
    self.assertResourceCalled('Execute', 'kill -9 `cat /var/run/storm/drpc.pid` >/dev/null 2>&1',
                              not_if = 'sleep 2; ! (ls /var/run/storm/drpc.pid >/dev/null 2>&1 && ps `cat /var/run/storm/drpc.pid` >/dev/null 2>&1) || sleep 20; ! (ls /var/run/storm/drpc.pid >/dev/null 2>&1 && ps `cat /var/run/storm/drpc.pid` >/dev/null 2>&1)',
                              ignore_failures=True
    )
    self.assertResourceCalled('Execute', 'rm -f /var/run/storm/drpc.pid')
    self.assertNoMoreResources()
