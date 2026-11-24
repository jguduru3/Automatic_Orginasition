// DistributorOS on Azure Container Apps.
// Two-phase deploy: deployApps=false creates the platform (registry, database, storage, environment),
// CI builds images into the registry, then deployApps=true creates/updates the two container apps.
targetScope = 'resourceGroup'

@description('Short lowercase prefix for resource names, e.g. dosprod')
@minLength(3)
@maxLength(12)
param prefix string
param location string = resourceGroup().location
param deployApps bool = false
param apiImage string = ''
param webImage string = ''

@secure()
param postgresAdminPassword string
@secure()
@minLength(32)
param jwtSecret string
@secure()
param whatsappAccessToken string = ''
@secure()
param whatsappAppSecret string = ''
@secure()
param whatsappVerifyToken string = ''

@allowed(['mock', 'cloud'])
param whatsappMode string = 'mock'
@allowed(['mock', 'http'])
param tallyMode string = 'mock'
param tallyUrl string = ''
param tallyCompany string = ''
@description('Load the fictional demo tenant and data. Use false for a real business.')
param loadDemoData bool = false
param postgresSku string = 'Standard_B1ms'
param postgresTier string = 'Burstable'
param apiMinReplicas int = 1
@description('Create Azure AI Speech (voice-note transcription) and Azure AI Vision (photo order reading).')
param enableAi bool = true
@description('Language for voice notes, e.g. hi-IN, mr-IN, en-IN')
param speechLanguage string = 'hi-IN'
param signupEnabled bool = false
param whatsappReminderTemplate string = ''
param whatsappDispatchTemplate string = ''
param whatsappPoTemplate string = ''
param apiMaxReplicas int = 5

var suffix = uniqueString(resourceGroup().id)
var pgName = '${prefix}-pg-${suffix}'
var dbName = 'dos'
var pgAdmin = 'dosadmin'

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${prefix}-logs'
  location: location
  properties: { sku: { name: 'PerGB2018' }, retentionInDays: 30 }
}

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${prefix}-id'
  location: location
}

resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: take('${prefix}acr${suffix}', 50)
  location: location
  sku: { name: 'Basic' }
  properties: { adminUserEnabled: false }
}

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: take('${prefix}st${suffix}', 24)
  location: location
  sku: { name: 'Standard_ZRS' }
  kind: 'StorageV2'
  properties: {
    minimumTlsVersion: 'TLS1_2'
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    supportsHttpsTrafficOnly: true
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
  properties: { deleteRetentionPolicy: { enabled: true, days: 14 } }
}

resource container 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  parent: blobService
  name: 'distributoros'
  properties: { publicAccess: 'None' }
}

// Built-in role ids
var acrPullRole = '7f951dda-4ed3-4680-a7ca-43fe172d538d'
var blobContributorRole = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'

resource acrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(acr.id, identity.id, acrPullRole)
  scope: acr
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPullRole)
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource blobWriter 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storage.id, identity.id, blobContributorRole)
  scope: storage
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', blobContributorRole)
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource pg 'Microsoft.DBforPostgreSQL/flexibleServers@2022-12-01' = {
  name: pgName
  location: location
  sku: { name: postgresSku, tier: postgresTier }
  properties: {
    version: '16'
    administratorLogin: pgAdmin
    administratorLoginPassword: postgresAdminPassword
    storage: { storageSizeGB: 32 }
    backup: { backupRetentionDays: 14, geoRedundantBackup: 'Disabled' }
    highAvailability: { mode: 'Disabled' }
  }
}

resource pgDb 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2022-12-01' = {
  parent: pg
  name: dbName
  properties: { charset: 'UTF8', collation: 'en_US.utf8' }
}

// Allows Azure-hosted services (Container Apps) to connect. For stricter isolation, move to VNet integration.
resource pgAzure 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2022-12-01' = {
  parent: pg
  name: 'AllowAzureServices'
  properties: { startIpAddress: '0.0.0.0', endIpAddress: '0.0.0.0' }
}

resource speech 'Microsoft.CognitiveServices/accounts@2023-05-01' = if (enableAi) {
  name: '${prefix}-speech-${suffix}'
  location: location
  kind: 'SpeechServices'
  sku: { name: 'S0' }
  properties: { customSubDomainName: '${prefix}-speech-${suffix}', publicNetworkAccess: 'Enabled' }
}

resource vision 'Microsoft.CognitiveServices/accounts@2023-05-01' = if (enableAi) {
  name: '${prefix}-vision-${suffix}'
  location: location
  kind: 'ComputerVision'
  sku: { name: 'S1' }
  properties: { customSubDomainName: '${prefix}-vision-${suffix}', publicNetworkAccess: 'Enabled' }
}

resource env 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${prefix}-env'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
  }
}

resource api 'Microsoft.App/containerApps@2024-03-01' = if (deployApps) {
  name: '${prefix}-api'
  location: location
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${identity.id}': {} } }
  dependsOn: [acrPull, blobWriter, pgDb, pgAzure]
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: { external: false, targetPort: 8080, transport: 'http', allowInsecure: true }
      registries: [{ server: acr.properties.loginServer, identity: identity.id }]
      secrets: [
        { name: 'db-password', value: postgresAdminPassword }
        { name: 'jwt-secret', value: jwtSecret }
        { name: 'wa-token', value: empty(whatsappAccessToken) ? 'unset' : whatsappAccessToken }
        { name: 'wa-secret', value: empty(whatsappAppSecret) ? 'unset' : whatsappAppSecret }
        { name: 'wa-verify', value: empty(whatsappVerifyToken) ? 'unset' : whatsappVerifyToken }
        { name: 'speech-key', value: enableAi ? speech.listKeys().key1 : 'unset' }
        { name: 'vision-key', value: enableAi ? vision.listKeys().key1 : 'unset' }
      ]
    }
    template: {
      containers: [{
        name: 'api'
        image: apiImage
        resources: { cpu: json('1.0'), memory: '2Gi' }
        env: [
          { name: 'DATABASE_URL', value: 'jdbc:postgresql://${pg.properties.fullyQualifiedDomainName}:5432/${dbName}?sslmode=require' }
          { name: 'DATABASE_USER', value: pgAdmin }
          { name: 'DATABASE_PASSWORD', secretRef: 'db-password' }
          { name: 'FLYWAY_LOCATIONS', value: loadDemoData ? 'classpath:db/migration,classpath:db/demo' : 'classpath:db/migration' }
          { name: 'JWT_SECRET', secretRef: 'jwt-secret' }
          { name: 'BLOB_MODE', value: 'azure' }
          { name: 'AZURE_STORAGE_ACCOUNT_URL', value: storage.properties.primaryEndpoints.blob }
          { name: 'AZURE_STORAGE_CONTAINER', value: container.name }
          { name: 'AZURE_CLIENT_ID', value: identity.properties.clientId }
          { name: 'WHATSAPP_MODE', value: whatsappMode }
          { name: 'WHATSAPP_ACCESS_TOKEN', secretRef: 'wa-token' }
          { name: 'WHATSAPP_APP_SECRET', secretRef: 'wa-secret' }
          { name: 'WHATSAPP_VERIFY_TOKEN', secretRef: 'wa-verify' }
          { name: 'TALLY_MODE', value: tallyMode }
          { name: 'TALLY_URL', value: tallyUrl }
          { name: 'TALLY_COMPANY', value: tallyCompany }
          { name: 'TALLY_AUTO_SYNC', value: tallyMode == 'http' ? 'true' : 'false' }
          { name: 'SIMULATE_ENABLED', value: loadDemoData ? 'true' : 'false' }
          { name: 'SWAGGER_ENABLED', value: 'false' }
          { name: 'SIGNUP_ENABLED', value: signupEnabled ? 'true' : 'false' }
          { name: 'AZURE_SPEECH_KEY', secretRef: 'speech-key' }
          { name: 'AZURE_SPEECH_REGION', value: location }
          { name: 'AZURE_SPEECH_LANGUAGE', value: speechLanguage }
          { name: 'AZURE_VISION_ENDPOINT', value: enableAi ? vision.properties.endpoint : '' }
          { name: 'AZURE_VISION_KEY', secretRef: 'vision-key' }
          { name: 'WHATSAPP_REMINDER_TEMPLATE', value: whatsappReminderTemplate }
          { name: 'WHATSAPP_DISPATCH_TEMPLATE', value: whatsappDispatchTemplate }
          { name: 'WHATSAPP_PO_TEMPLATE', value: whatsappPoTemplate }
          { name: 'DB_POOL_SIZE', value: '15' }
        ]
        probes: [
          { type: 'Startup', httpGet: { path: '/actuator/health/liveness', port: 8080 }, initialDelaySeconds: 10, periodSeconds: 5, failureThreshold: 30 }
          { type: 'Liveness', httpGet: { path: '/actuator/health/liveness', port: 8080 }, periodSeconds: 15 }
          { type: 'Readiness', httpGet: { path: '/actuator/health/readiness', port: 8080 }, periodSeconds: 10 }
        ]
      }]
      scale: {
        minReplicas: apiMinReplicas
        maxReplicas: apiMaxReplicas
        rules: [{ name: 'http', http: { metadata: { concurrentRequests: '50' } } }]
      }
    }
  }
}

resource web 'Microsoft.App/containerApps@2024-03-01' = if (deployApps) {
  name: '${prefix}-web'
  location: location
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${identity.id}': {} } }
  dependsOn: [acrPull]
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: { external: true, targetPort: 8080, transport: 'auto', allowInsecure: false }
      registries: [{ server: acr.properties.loginServer, identity: identity.id }]
    }
    template: {
      containers: [{
        name: 'web'
        image: webImage
        resources: { cpu: json('0.25'), memory: '0.5Gi' }
        env: [{ name: 'API_URL', value: 'http://${prefix}-api' }]
        probes: [{ type: 'Liveness', httpGet: { path: '/healthz', port: 8080 }, periodSeconds: 15 }]
      }]
      scale: { minReplicas: 1, maxReplicas: 3, rules: [{ name: 'http', http: { metadata: { concurrentRequests: '100' } } }] }
    }
  }
}

output acrName string = acr.name
output acrLoginServer string = acr.properties.loginServer
output webUrl string = deployApps ? 'https://${web.properties.configuration.ingress.fqdn}' : ''
output whatsappWebhookUrl string = deployApps ? 'https://${web.properties.configuration.ingress.fqdn}/api/webhooks/whatsapp' : ''
output postgresHost string = pg.properties.fullyQualifiedDomainName
