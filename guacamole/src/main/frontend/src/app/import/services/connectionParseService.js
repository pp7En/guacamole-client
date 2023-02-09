/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * 'License'); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/* global _ */

import { parse as parseCSVData } from 'csv-parse/lib/sync'
import { parse as parseYAMLData } from 'yaml'

/**
 * A service for parsing user-provided JSON, YAML, or JSON connection data into
 * an appropriate format for bulk uploading using the PATCH REST endpoint.
 */
angular.module('import').factory('connectionParseService',
        ['$injector', function connectionParseService($injector) {

    // Required types
    const Connection          = $injector.get('Connection');
    const DirectoryPatch      = $injector.get('DirectoryPatch');
    const ParseError          = $injector.get('ParseError');
    const ParseResult         = $injector.get('ParseResult');
    const TranslatableMessage = $injector.get('TranslatableMessage');
    
    // Required services
    const $q                     = $injector.get('$q');
    const $routeParams           = $injector.get('$routeParams');
    const schemaService          = $injector.get('schemaService');
    const connectionCSVService   = $injector.get('connectionCSVService');
    const connectionGroupService = $injector.get('connectionGroupService');
    const userService            = $injector.get('userService');
    const userGroupService       = $injector.get('userGroupService');

    const service = {};

    /**
     * Resolves to an object whose keys are all valid identifiers in the current
     * data source. The provided `requestFunction` should resolve to such an
     * object when provided with the current data source.
     *
     * @param {Function.<String<Object.<String, *>>} requestFunction
     *     A function that, given a data source, will return a promise resolving
     *     to an object with keys that are unique identifiers for entities in
     *     that data source.
     *
     * @returns {Promise.<Function[String[], String[]>>}
     *     A promise that will resolve to an function that, given an array of
     *     identifiers, will return all identifiers that do not exist as keys in
     *     the object returned by `requestFunction`, i.e. all identifiers that
     *     do not exist in the current data source.
     */
    function getIdentifierMap(requestFunction) {

        // The current data source to which all the identifiers will belong
        const dataSource = $routeParams.dataSource;

        // Make the request and return the response, which should be an object
        // whose keys are all valid identifiers for the current data source
        return requestFunction(dataSource);
    }

    /**
     * Return a promise that resolves to a function that takes an array of
     * identifiers, returning a ParseError describing the invalid identifiers
     * from the list. The provided `requestFunction` should resolve to such an
     * object whose keys are all valid identifiers when provided with the
     * current data source.
     *
     * @param {Function.<String<Object.<String, *>>} requestFunction
     *     A function that, given a data source, will return a promise resolving
     *     to an object with keys that are unique identifiers for entities in
     *     that data source.
     *
     * @returns {Promise.<Function.<String[], ParseError?>>}
     *     A promise that will resolve to a function to check the validity of
     *     each identifier in a provided array, returning a ParseError 
     *     describing the problem if any are not valid.
     */
    function getInvalidIdentifierErrorChecker(requestFunction) {

        // Fetch all the valid user identifiers in the system, and
        return getIdentifierMap(requestFunction).then(validIdentifiers =>

            // The resolved function that takes a list of user group identifiers
            allIdentifiers => {

                // Filter to only include invalid identifiers
                const invalidIdentifiers = _.filter(allIdentifiers,
                    identifier => !validIdentifiers[identifier]);

                if (invalidIdentifiers.length) {

                    // Quote and comma-seperate for display
                    const identifierList = invalidIdentifiers.map(
                        identifier => '"' + identifier + '"').join(', ');

                    return new ParseError({
                        message: 'Invalid User Group Identifiers: ' + identifierList,
                        key: 'CONNECTION_IMPORT.ERROR_INVALID_USER_GROUP_IDENTIFIERS',
                        variables: { IDENTIFIER_LIST: identifierList }
                    });
                }

            });
    }
    
    /**
     * Perform basic checks, common to all file types - namely that the parsed
     * data is an array, and contains at least one connection entry. Returns an 
     * error if any of these basic checks fails.
     * 
     * returns {ParseError}
     *     An error describing the parsing failure, if one of the basic checks
     *     fails.
     */
    function performBasicChecks(parsedData) {
        
        // Make sure that the file data parses to an array (connection list)
        if (!(parsedData instanceof Array))
            return new ParseError({
                message: 'Import data must be a list of connections',
                key: 'CONNECTION_IMPORT.ERROR_ARRAY_REQUIRED'
            });

        // Make sure that the connection list is not empty - contains at least
        // one connection
        if (!parsedData.length)
            return new ParseError({
                message: 'The provided file is empty',
                key: 'CONNECTION_IMPORT.ERROR_EMPTY_FILE'
            });
    }
    
    
    /**
     * Returns a promise that resolves to an object mapping potential groups
     * that might be encountered in an imported connection to group identifiers.
     * 
     * The idea is that a user-provided import file might directly specify a
     * parentIdentifier, or it might specify a named group path like "ROOT",
     * "ROOT/parent", or "ROOT/parent/child". This object resolved by the 
     * promise returned from this function will map all of the above to the 
     * identifier of the appropriate group, if defined.
     * 
     * @returns {Promise.<Object>}
     */
    function getGroupLookups() {
        
        // The current data source - defines all the groups that the connections
        // might be imported into
        const dataSource = $routeParams.dataSource;
        
        const deferredGroupLookups = $q.defer();
        
        connectionGroupService.getConnectionGroupTree(dataSource).then(
                rootGroup => {
            
            const groupLookup = {};
            
            // Add the specified group to the lookup, appending all specified
            // prefixes, and then recursively call saveLookups for all children
            // of the group, appending to the prefix for each level
            function saveLookups(prefix, group) {
                
                // To get the path for the current group, add the name
                const currentPath = prefix + group.name;
                
                // Add the current path to the lookup
                groupLookup[currentPath] = group.identifier;
                
                // Add each child group to the lookup
                const nextPrefix = currentPath + "/";
                _.forEach(group.childConnectionGroups,
                        childGroup => saveLookups(nextPrefix, childGroup));
            }
            
            // Start at the root group
            saveLookups("", rootGroup);
            
            // Resolve with the now fully-populated lookups
            deferredGroupLookups.resolve(groupLookup);
            
        });
        
        return deferredGroupLookups.promise;
    }

    /**
     * Returns a promise that will resolve to a transformer function that will
     * take an object that may contain a "group" field, replacing it if present 
     * with a "parentIdentifier". If both a "group" and "parentIdentifier" field
     * are present on the provided object, or if no group exists at the specified
     * path, the function will throw a ParseError describing the failure.
     *
     * @returns {Promise.<Function<Object, Object>>}
     *     A promise that will resolve to a function that will transform a
     *     "group" field into a "parentIdentifier" field if possible.
     */
    function getGroupTransformer() {
        return getGroupLookups().then(lookups => connection => {

            // If there's no group to translate, do nothing
            if (!connection.group)
                return;

            // If both are specified, the parent group is ambigious
            if (connection.parentIdentifier)
                throw new ParseError({
                    message: 'Only one of group or parentIdentifier can be set',
                    key: 'CONNECTION_IMPORT.ERROR_AMBIGUOUS_PARENT_GROUP'
                });

            // Look up the parent identifier for the specified group path
            const identifier = lookups[connection.group];

            // If the group doesn't match anything in the tree
            if (!identifier)
                throw new ParseError({
                    message: 'No group found named: ' + connection.group,
                    key: 'CONNECTION_IMPORT.ERROR_INVALID_GROUP',
                    variables: { GROUP: connection.group }
                });

            // Set the parent identifier now that it's known
            return {
                ...connection,
                parentIdentifier: identifier
            };

        });
    }

    /**
     * Convert a provided ProtoConnection array into a ParseResult. Any provided
     * transform functions will be run on each entry in `connectionData` before
     * any other processing is done.
     *
     * @param {*[]} connectionData
     *     An arbitrary array of data. This must evaluate to a ProtoConnection
     *     object after being run through all functions in `transformFunctions`.
     *
     * @param {Function[]} transformFunctions
     *     An array of transformation functions to run on each entry in
     *     `connection` data.
     *
     * @return {Promise.<Object>}
     *     A promise resolving to ParseResult object representing the result of
     *     parsing all provided connection data.
     */
    function parseConnectionData(connectionData, transformFunctions) {
        
        // Check that the provided connection data array is not empty
        const checkError = performBasicChecks(connectionData);
        if (checkError) {
            const deferred = $q.defer();
            deferred.reject(checkError);
            return deferred.promise;
        }

        return $q.all({
            groupTransformer           : getGroupTransformer(),
            invalidUserIdErrorDetector : getInvalidIdentifierErrorChecker(
                    userService.getUsers),
            invalidGroupIDErrorDetector : getInvalidIdentifierErrorChecker(
                    userGroupService.getUserGroups),
        })

        // Transform the rows from the CSV file to an array of API patches
        // and lists of user and group identifiers
        .then(({groupTransformer,
                invalidUserIdErrorDetector, invalidGroupIDErrorDetector}) =>
                    connectionData.reduce((parseResult, data) => {

            const { patches, identifiers, users, groups } = parseResult;

            // Run the array data through each provided transform
            let connectionObject = data;
            _.forEach(transformFunctions, transform => {
                connectionObject = transform(connectionObject);
            });

            // All errors found while parsing this connection
            const connectionErrors = [];
            parseResult.errors.push(connectionErrors);

            // Translate the group on the object to a parentIdentifier
            try {
                connectionObject = groupTransformer(connectionObject);
            }

            // If there was a problem with the group or parentIdentifier
            catch (error) {
                connectionErrors.push(error);
            }

            // Push any errors for invalid user or user group identifiers
            const pushError = error => error && connectionErrors.push(error);
            pushError(invalidUserIdErrorDetector(connectionObject.users));
            pushError(invalidGroupIDErrorDetector(connectionObject.userGroups));

            // Add the user and group identifiers for this connection
            users.push(connectionObject.users);
            groups.push(connectionObject.groups);

            // Translate to a full-fledged Connection
            const connection = new Connection(connectionObject);

            // Finally, add a patch for creating the connection
            patches.push(new DirectoryPatch({
                op: 'add',
                path: '/',
                value: connection
            }));

            // If there are any errors for this connection fail the whole batch 
            if (connectionErrors.length)
                parseResult.hasErrors = true;

            return parseResult;

        }, new ParseResult()));
    }

    /**
     * Convert a provided CSV representation of a connection list into a JSON
     * object to be submitted to the PATCH REST endpoint, as well as a list of
     * objects containing lists of user and user group identifiers to be granted
     * to each connection.
     *
     * @param {String} csvData
     *     The CSV-encoded connection list to process.
     *
     * @return {Promise.<Object>}
     *     A promise resolving to ParseResult object representing the result of
     *     parsing all provided connection data.
     */
    service.parseCSV = function parseCSV(csvData) {
        
        // Convert to an array of arrays, one per CSV row (including the header)
        // NOTE: skip_empty_lines is required, or a trailing newline will error
        let parsedData;
        try {
            parsedData = parseCSVData(csvData, {skip_empty_lines: true});
        } 
        
        // If the CSV parser throws an error, reject with that error. No
        // translation key will be available here.
        catch(error) {
            console.error(error);
            const deferred = $q.defer();
            deferred.reject(new ParseError({ message: error.message }));
            return deferred.promise;
        }

        // The header row - an array of string header values
        const header = parsedData.length ? parsedData[0] : [];

        // Slice off the header row to get the data rows
        const connectionData = parsedData.slice(1);

        // Generate the CSV transform function, and apply it to every row
        // before applying all the rest of the standard transforms
        return connectionCSVService.getCSVTransformer(header).then(
            csvTransformer =>

                // Apply the CSV transform to every row
                parseConnectionData(connectionData, [csvTransformer]));

    };

    /**
     * Convert a provided YAML representation of a connection list into a JSON
     * object to be submitted to the PATCH REST endpoint, as well as a list of
     * objects containing lists of user and user group identifiers to be granted
     * to each connection.
     *
     * @param {String} yamlData
     *     The YAML-encoded connection list to process.
     *
     * @return {Promise.<Object>}
     *     A promise resolving to ParseResult object representing the result of
     *     parsing all provided connection data.
     */
    service.parseYAML = function parseYAML(yamlData) {

        // Parse from YAML into a javascript array
        const connectionData = parseYAMLData(yamlData);
        
        // Produce a ParseResult
        return parseConnectionData(connectionData);
    };

    /**
     * Convert a provided JSON-encoded representation of a connection list into
     * an array of patches to be submitted to the PATCH REST endpoint, as well
     * as a list of objects containing lists of user and user group identifiers
     * to be granted to each connection.
     *
     * @param {String} jsonData
     *     The JSON-encoded connection list to process.
     *
     * @return {Promise.<Object>}
     *     A promise resolving to ParseResult object representing the result of
     *     parsing all provided connection data.
     */
    service.parseJSON = function parseJSON(jsonData) {

        // Parse from JSON into a javascript array
        const connectionData = JSON.parse(jsonData);

        // Produce a ParseResult
        return parseConnectionData(connectionData);

    };

    return service;

}]);
