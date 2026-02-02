/*
 * PartyServices.groovy
 * 
 * Groovy service implementations for Party management.
 * Integrates with durion-positivity backend via REST API bridge.
 * 
 * All backend calls go through runtime/component/durion-positivity/ bridge component.
 */

// Create Commercial Account
def createCommercialAccount() {
    def ec = context.ec
    
    try {
        // Build external identifiers map if provided
        def externalIdentifiers = [:]
        if (context.externalIdentifierType && context.externalIdentifierValue) {
            externalIdentifiers[context.externalIdentifierType] = context.externalIdentifierValue
        }
        
        // Build request payload
        def requestBody = [
            legalName: context.legalName,
            defaultBillingTermsId: context.defaultBillingTermsId
        ]
        
        if (context.dbaName) requestBody.dbaName = context.dbaName
        if (context.taxId) requestBody.taxId = context.taxId
        if (!externalIdentifiers.isEmpty()) requestBody.externalIdentifiers = externalIdentifiers
        
        // Add duplicate override fields if provided
        if (context.duplicateOverride) {
            requestBody.duplicateOverride = true
            requestBody.duplicateOverrideJustification = context.duplicateOverrideJustification
            
            if (context.duplicateCandidatePartyIds) {
                requestBody.duplicateCandidatePartyIds = context.duplicateCandidatePartyIds.split(',').toList()
            }
        }
        
        // Call backend via durion-positivity bridge
        // NOTE: Backend endpoint returns 501 (Not Implemented) - graceful degradation
        def response = ec.service.sync()
            .name('durion.positivity.RestClient.post')
            .parameter('endpoint', '/v1/crm/accounts/parties')
            .parameter('body', requestBody)
            .call()
        
        // Handle response
        if (response.statusCode == 201 || response.statusCode == 200) {
            // Success
            context.partyId = response.body?.partyId
            context.createdAt = response.body?.createdAt
            context.createdBy = response.body?.createdBy
            
        } else if (response.statusCode == 409) {
            // Duplicate candidates found
            context.duplicateCandidates = response.body?.duplicateCandidates ?: []
            ec.message.addError("Potential duplicate accounts found. Please review and provide justification to proceed.")
            
        } else if (response.statusCode == 400) {
            // Validation errors
            def fieldErrors = response.body?.fieldErrors
            if (fieldErrors) {
                fieldErrors.each { field, message ->
                    ec.message.addError("${field}: ${message}")
                }
            } else {
                ec.message.addError(response.body?.message ?: "Validation error occurred")
            }
            
        } else if (response.statusCode == 403) {
            // Forbidden
            ec.message.addError("Access denied: You don't have permission to create commercial accounts")
            
        } else if (response.statusCode == 501) {
            // Not Implemented - graceful degradation
            ec.message.addError("Backend service not yet implemented (501). This is a placeholder implementation.")
            
        } else {
            // Other errors
            def correlationId = response.headers?.'X-Correlation-Id' ?: response.body?.correlationId
            if (correlationId) {
                ec.web.parameters.correlationId = correlationId
            }
            ec.message.addError("Unable to create account: ${response.body?.message ?: 'Unknown error'}")
        }
        
    } catch (Exception e) {
        ec.logger.error("Error creating commercial account", e)
        ec.message.addError("System error: ${e.message}")
    }
}

// Get Party
def getParty() {
    def ec = context.ec
    
    try {
        // Call backend to get party details
        def response = ec.service.sync()
            .name('durion.positivity.RestClient.get')
            .parameter('endpoint', "/v1/crm/accounts/parties/${context.partyId}")
            .call()
        
        if (response.statusCode == 200) {
            // Success
            context.party = response.body
            
        } else if (response.statusCode == 409) {
            // Party merged
            context.errorCode = response.body?.errorCode
            context.mergedToPartyId = response.body?.mergedToPartyId
            
        } else if (response.statusCode == 404) {
            // Not found
            context.errorCode = 'NOT_FOUND'
            
        } else if (response.statusCode == 403) {
            // Forbidden
            context.errorCode = 'FORBIDDEN'
            
        } else if (response.statusCode == 501) {
            // Not Implemented - return mock data for now
            context.party = [
                partyId: context.partyId,
                legalName: 'Mock Commercial Account (Backend 501)',
                partyType: 'ORGANIZATION',
                status: 'ACTIVE',
                createdAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
            ]
        } else {
            ec.message.addError("Unable to retrieve party: ${response.body?.message ?: 'Unknown error'}")
        }
        
    } catch (Exception e) {
        ec.logger.error("Error getting party", e)
        ec.message.addError("System error: ${e.message}")
    }
}

// Search Parties
def searchParties() {
    def ec = context.ec
    
    try {
        // Require at least one search criterion (no browse-all per DECISION-INVENTORY-014)
        if (!context.name && !context.email && !context.phone && !context.taxId) {
            ec.message.addError("At least one search criterion is required")
            context.results = []
            context.totalCount = 0
            return
        }
        
        // Build request payload
        def requestBody = [
            pageNumber: context.pageNumber ?: 1,
            pageSize: context.pageSize ?: 20,
            sortField: context.sortField ?: 'legalName',
            sortOrder: context.sortOrder ?: 'ASC'
        ]
        
        if (context.name) requestBody.name = context.name
        if (context.email) requestBody.email = context.email
        if (context.phone) requestBody.phone = context.phone
        if (context.taxId) requestBody.taxId = context.taxId
        if (context.partyType) requestBody.partyType = context.partyType
        if (context.status) requestBody.status = context.status
        if (context.includeMerged) requestBody.includeMerged = context.includeMerged
        
        // Call backend search endpoint
        def response = ec.service.sync()
            .name('durion.positivity.RestClient.post')
            .parameter('endpoint', '/v1/crm/accounts/parties/search')
            .parameter('body', requestBody)
            .call()
        
        if (response.statusCode == 200) {
            context.results = response.body?.results ?: []
            context.totalCount = response.body?.totalCount ?: 0
            context.pageNumber = response.body?.pageNumber ?: 1
            context.pageSize = response.body?.pageSize ?: 20
            
        } else if (response.statusCode == 501) {
            // Not Implemented - return empty results
            context.results = []
            context.totalCount = 0
            ec.message.addMessage("Backend search not yet implemented (501)")
            
        } else {
            ec.message.addError("Search failed: ${response.body?.message ?: 'Unknown error'}")
            context.results = []
            context.totalCount = 0
        }
        
    } catch (Exception e) {
        ec.logger.error("Error searching parties", e)
        ec.message.addError("System error: ${e.message}")
        context.results = []
        context.totalCount = 0
    }
}

// Check Duplicate Parties
def checkDuplicateParties() {
    def ec = context.ec
    
    try {
        def requestBody = [:]
        if (context.legalName) requestBody.legalName = context.legalName
        if (context.taxId) requestBody.taxId = context.taxId
        if (context.externalIdentifiers) requestBody.externalIdentifiers = context.externalIdentifiers
        
        // Call backend duplicate check
        def response = ec.service.sync()
            .name('durion.positivity.RestClient.post')
            .parameter('endpoint', '/v1/crm/accounts/parties/duplicate-check')
            .parameter('body', requestBody)
            .call()
        
        if (response.statusCode == 200) {
            context.candidates = response.body?.candidates ?: []
        } else {
            context.candidates = []
        }
        
    } catch (Exception e) {
        ec.logger.error("Error checking duplicates", e)
        context.candidates = []
    }
}

// List Billing Terms
def listBillingTerms() {
    def ec = context.ec
    
    try {
        // Call Billing domain endpoint to get billing terms list
        def response = ec.service.sync()
            .name('durion.positivity.RestClient.get')
            .parameter('endpoint', '/v1/billing/terms')
            .parameter('queryParams', [activeOnly: context.activeOnly ?: true])
            .call()
        
        if (response.statusCode == 200) {
            context.items = response.body?.items ?: []
        } else if (response.statusCode == 501) {
            // Not Implemented - return mock billing terms
            context.items = [
                [billingTermsId: 'NET30', code: 'NET30', description: 'Net 30 Days', active: true],
                [billingTermsId: 'NET60', code: 'NET60', description: 'Net 60 Days', active: true],
                [billingTermsId: 'COD', code: 'COD', description: 'Cash on Delivery', active: true],
                [billingTermsId: 'PREPAY', code: 'PREPAY', description: 'Prepayment Required', active: true]
            ]
        } else {
            context.items = []
            ec.message.addMessage("Unable to load billing terms")
        }
        
    } catch (Exception e) {
        ec.logger.error("Error listing billing terms", e)
        // Return default billing terms on error
        context.items = [
            [billingTermsId: 'NET30', code: 'NET30', description: 'Net 30 Days', active: true]
        ]
    }
}

// Placeholder implementations for other services
// These will be fully implemented in subsequent phases

def createPerson() {
    // TODO: Implement for Story #96
    context.ec.message.addError("Person creation not yet implemented")
}

def updateParty() {
    // TODO: Implement for edit flows
    context.ec.message.addError("Party update not yet implemented")
}

def createPartyRelationship() {
    // TODO: Implement for Story #97
    context.ec.message.addError("Party relationship creation not yet implemented")
}

def listPartyRelationships() {
    // TODO: Implement for Story #97
    context.items = []
    context.total = 0
}

def setPrimaryBillingContact() {
    // TODO: Implement for Story #97
    context.ec.message.addError("Set primary billing not yet implemented")
}

def deactivatePartyRelationship() {
    // TODO: Implement for Story #97
    context.ec.message.addError("Deactivate relationship not yet implemented")
}

def mergeParties() {
    // TODO: Implement for Story #98
    context.ec.message.addError("Party merge not yet implemented")
}

// Return closures for service execution
return [
    createCommercialAccount: this.&createCommercialAccount,
    getParty: this.&getParty,
    searchParties: this.&searchParties,
    checkDuplicateParties: this.&checkDuplicateParties,
    listBillingTerms: this.&listBillingTerms,
    createPerson: this.&createPerson,
    updateParty: this.&updateParty,
    createPartyRelationship: this.&createPartyRelationship,
    listPartyRelationships: this.&listPartyRelationships,
    setPrimaryBillingContact: this.&setPrimaryBillingContact,
    deactivatePartyRelationship: this.&deactivatePartyRelationship,
    mergeParties: this.&mergeParties
]
