#!/usr/bin/env python
"""
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

"""

from resource_management import *
from yaml_utils import escape_yaml_propetry
import sys

def storm():
  import params

  Directory([params.log_dir, params.pid_dir, params.local_dir, params.conf_dir],
            owner=params.storm_user,
            group=params.user_group,
            recursive=True
  )

  File(format("{conf_dir}/config.yaml"),
       content=Template("config.yaml.j2"),
       owner=params.storm_user,
       group=params.user_group
  )

  configurations = params.config['configurations']['storm-site']
  
  File(format("{conf_dir}/storm.yaml"),
       content=Template(
                        "storm.yaml.j2", 
                         extra_imports=[escape_yaml_propetry], 
                        configurations = configurations),
       owner=params.storm_user,
       group=params.user_group
  )

  File(format("{conf_dir}/storm-env.sh"),
    owner=params.storm_user,
    content=InlineTemplate(params.storm_env_sh_template)
  )

  if params.security_enabled:
    TemplateConfig(format("{conf_dir}/storm_jaas.conf"),
                   owner=params.storm_user
    )