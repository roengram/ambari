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
'use strict';

angular.module('ambariAdminConsole')
.controller('CreateViewInstanceCtrl',['$scope', 'View', 'Alert', 'Auth', '$routeParams', '$location', function($scope, View, Alert, Auth, $routeParams, $location) {
  $scope.form = {};

  function loadMeta(){
    View.getMeta($routeParams.viewId, $scope.version).then(function(data) {
      var viewVersion = data.data;
      var pattern = /{username}/;
      viewVersion.ViewVersionInfo.parameters = viewVersion.ViewVersionInfo.parameters.map(function (item) {
        var parameter = item;
        if (pattern.test(item.description)) {
          parameter.description = item.description.replace(pattern, Auth.getCurrentUser());
        }
        return parameter;
      });
      $scope.view = viewVersion;

      $scope.instance = {
        view_name: viewVersion.ViewVersionInfo.view_name,
        version: viewVersion.ViewVersionInfo.version,
        instance_name: '',
        label: '',
        visible: true,
        icon_path: '',
        icon64_path: '',
        properties: viewVersion.ViewVersionInfo.parameters,
        description: ''
      };    
    });
  }
    

  $scope.$watch(function(scope) {
    return scope.version;
  }, function(version) {
    if( version ){
      loadMeta();
    }
  });

  // $scope.view = viewVersion;
  $scope.isAdvancedClosed = true;
  $scope.instanceExists = false;

  $scope.versions = [];
  $scope.version = null;

  View.getVersions($routeParams.viewId).then(function(versions) {
    $scope.versions = versions;
    $scope.version = $scope.versions[$scope.versions.length-1];
  });


  $scope.nameValidationPattern = /^\s*\w*\s*$/;

  $scope.save = function() {
    $scope.form.instanceCreateForm.submitted = true;
    if($scope.form.instanceCreateForm.$valid){
      View.createInstance($scope.instance)
        .then(function(data) {
          Alert.success('Created View Instance ' + $scope.instance.instance_name);
          $location.path('/views/' + $scope.instance.view_name + '/versions/' + $scope.instance.version + '/instances/' + $scope.instance.instance_name + '/edit');
        })
        .catch(function(data) {
          Alert.error('Cannot create instance', data.data.message);
        });
    }
  };
}]);
