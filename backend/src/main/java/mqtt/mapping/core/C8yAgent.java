package mqtt.mapping.core;

import c8y.IsDevice;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.model.JSONBase;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.measurement.MeasurementValue;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.identity.ExternalIDCollection;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationService;
import mqtt.mapping.configuration.MQTTConfiguration;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.MappingsRepresentation;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.service.ServiceStatus;
import mqtt.mapping.service.MQTTClient;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.*;

@Slf4j
@Service
public class C8yAgent {

    @Autowired
    private EventApi eventApi;

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private IdentityApi identityApi;

    @Autowired
    private MeasurementApi measurementApi;

    @Autowired
    private AlarmApi alarmApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private MQTTClient mqttClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigurationService configurationService;

    private ManagedObjectRepresentation agentMOR;

    private final String AGENT_ID = "MQTT_MAPPING_SERVICE";
    private final String AGENT_NAME = "MQTT Mapping Service";
    private final String MQTT_MAPPING_TYPE = "c8y_mqttMapping";
    private final String MQTT_MAPPING_FRAGMENT = "c8y_mqttMapping";
    public String tenant = null;

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        tenant = event.getCredentials().getTenant();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        /* Connecting to Cumulocity */
        subscriptionsService.runForTenant(tenant, () -> {
            // register agent
            ExternalIDRepresentation agentIdRep = null;
            try {
                agentIdRep = getExternalId(AGENT_ID, null);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            if (agentIdRep != null) {
                log.info("Agent with ID {} already exists {}", AGENT_ID, agentIdRep);
                this.agentMOR = agentIdRep.getManagedObject();
            } else {
                ManagedObjectRepresentation agent = new ManagedObjectRepresentation();
                agent.setName(AGENT_NAME);
                agent.set(new Agent());
                agent.set(new IsDevice());
                this.agentMOR = inventoryApi.create(agent);
                log.info("Agent has been created with ID {}", agentMOR.getId());
                ExternalIDRepresentation externalAgentId = createExternalID(this.agentMOR, AGENT_ID, "c8y_Serial");
                log.info("ExternalId created: {}", externalAgentId.getExternalId());
            }

            // test if managedObject mqttMapping exists
            ExternalIDRepresentation moMappingExtId = getExternalId(MQTT_MAPPING_TYPE, "c8y_Serial");
            if (moMappingExtId == null) {
                // create new managedObject
                ManagedObjectRepresentation moMapping = new ManagedObjectRepresentation();
                moMapping.setType(MQTT_MAPPING_TYPE);
                // moMapping.set([], MQTT_MAPPING_FRAGMENT);
                moMapping.setProperty(MQTT_MAPPING_FRAGMENT, new ArrayList<>());
                moMapping.setName("MQTT-Mapping");
                moMapping = inventoryApi.create(moMapping);
                createExternalID(moMapping, MQTT_MAPPING_TYPE, "c8y_Serial");
                log.info("Created new MQTT-Mapping: {}, {}", moMapping.getId().getValue(), moMapping.getId());
            }
        });

        try {
            mqttClient.submitInitialize();
            mqttClient.submitConnect();
            mqttClient.runHouskeeping();
        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            mqttClient.submitConnect();
        }
    }

    @PreDestroy
    private void stop() {
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
    }

    public ExternalIDRepresentation createExternalID(ManagedObjectRepresentation mor, String externalId,
            String externalIdType) {
        /* Connecting to Cumulocity */
        ExternalIDRepresentation externalID = new ExternalIDRepresentation();
        externalID.setType(externalIdType);
        externalID.setExternalId(externalId);
        externalID.setManagedObject(mor);
        try {
            externalID = identityApi.create(externalID);
        } catch (SDKException e) {
            log.error(e.getMessage());
        }

        return externalID;

    }

    public MeasurementRepresentation storeMeasurement(ManagedObjectRepresentation mor,
            String eventType, DateTime timestamp, Map<String, Object> attributes, Map<String, Object> fragments)
            throws SDKException {

        MeasurementRepresentation measure = new MeasurementRepresentation();
        measure.setAttrs(attributes);
        measure.setSource(mor);
        measure.setType(eventType);
        measure.setDateTime(timestamp);

        // Step 3: Iterate over all fragments provided
        Iterator<Map.Entry<String, Object>> fragmentKeys = fragments.entrySet().iterator();
        while (fragmentKeys.hasNext()) {
            Map.Entry<String, Object> currentFragment = fragmentKeys.next();
            measure.set(currentFragment.getValue(), currentFragment.getKey());

        }
        measure = measurementApi.create(measure);
        return measure;
    }

    public ExternalIDRepresentation getExternalId(String externalId, String type) {
        if (type == null) {
            type = "c8y_Serial";
        }
        ID id = new ID();
        id.setType(type);
        id.setValue(externalId);
        ExternalIDRepresentation[] extIds = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                extIds[0] = identityApi.getExternalId(id);
            } catch (SDKException e) {
                log.info("External ID {} not found", externalId);
            }
        });
        return extIds[0];
    }

    public ExternalIDRepresentation getMoExternalId(final String sourceId, String externalType){
        if (externalType == null) {
            externalType = "c8y_Serial";
        }
        log.info("check out deviceId with {} for external {}", sourceId, externalType);
        ExternalIDRepresentation[] _result = {null};
        String _finalExternalType = externalType;
        subscriptionsService.runForTenant(tenant,()->{
            ExternalIDCollection externalIDCollection = identityApi.getExternalIdsOfGlobalId(GId.asGId(sourceId));
            for(ExternalIDRepresentation eid : externalIDCollection.get().allPages()){
                if(eid.getType().equalsIgnoreCase(_finalExternalType)){
                    _result[0] = eid;
                }
            }
        });
        return _result[0];
    }

    public void unregisterDevice(String externalId) {
        ExternalIDRepresentation retExternalId = getExternalId(externalId, null);
        if (retExternalId != null) {
            inventoryApi.delete(retExternalId.getManagedObject().getId());
            identityApi.deleteExternalId(retExternalId);
        }
    }

    public void storeEvent(EventRepresentation event) {
        eventApi.createAsync(event).get();
    }

    public ManagedObjectRepresentation getAgentMOR() {
        return agentMOR;
    }

    public void createIdentity(ExternalIDRepresentation externalIDGid) {
        identityApi.create(externalIDGid);
    }

    public ManagedObjectRepresentation createMO(ManagedObjectRepresentation mor) {
        return inventoryApi.create(mor);
    }

    public void addChildDevice(ManagedObjectReferenceRepresentation child2Ref) {
        inventoryApi.getManagedObjectApi(agentMOR.getId()).addChildDevice(child2Ref);
    }

    public void addChildDevice(ManagedObjectReferenceRepresentation child2Ref,
            ManagedObjectRepresentation parent) {
        inventoryApi.getManagedObjectApi(parent.getId()).addChildAssets(child2Ref);
    }

    public MeasurementRepresentation createMeasurement(String name, String type, ManagedObjectRepresentation mor,
            DateTime dateTime, HashMap<String, MeasurementValue> mvMap) {
        MeasurementRepresentation mr = new MeasurementRepresentation();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                mr.set(mvMap, name);
                mr.setType(type);
                mr.setSource(mor);
                mr.setDateTime(dateTime);
                log.debug("Creating Measurement {}", mr);
                MeasurementRepresentation mrn = measurementApi.create(mr);
                mr.setId(mrn.getId());
            } catch (SDKException e) {
                log.error("Error creating Measurement", e);
            }
        });
        return mr;
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor) {
        AlarmRepresentation[] ars = { new AlarmRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            ars[0].setSeverity(severity);
            ars[0].setSource(parentMor);
            ars[0].setText(message);
            ars[0].setDateTime(alarmTime);
            ars[0].setStatus("ACTIVE");
            ars[0].setType(type);

            ars[0] = this.alarmApi.create(ars[0]);
        });
        return ars[0];
    }

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor) {
        EventRepresentation[] ers = { new EventRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            ers[0].setSource(parentMor != null ? parentMor : agentMOR);
            ers[0].setText(message);
            ers[0].setDateTime(eventTime);
            ers[0].setType(type);
            this.eventApi.createAsync(ers[0]);
        });
    }

    public ArrayList<Mapping> getMappings() {
        ArrayList<Mapping> result = new ArrayList<Mapping>();
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            try {
                String moJson = JSONBase.getJSONGenerator().forValue(mo);
//                log.info("mapping json:{}" , moJson);
                MappingsRepresentation mqttMo = objectMapper.readValue(moJson, MappingsRepresentation.class);
//                MappingsRepresentation mqttMo = objectMapper.convertValue(mo,MappingsRepresentation.class);
                log.info("Found Mapping {}", mqttMo);
                result.addAll(mqttMo.getC8yMQTTMapping());
                log.info("Found Mapping {}", result.size());
            }catch(Exception e){
                log.error(e.getMessage(),e);
            }
        });
        return result;
    }

    public MQTTConfiguration loadConfiguration() {
        MQTTConfiguration[] results = { new MQTTConfiguration() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadConfiguration();
            log.info("Found configuration {}", results[0]);
        });
        return results[0];
    }

    public void saveConfiguration(MQTTConfiguration configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveConfiguration(configuration);
                log.info("Saved configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public void createMEA(API targetAPI, String payload) throws ProcessingException {
        String[] errors = { "" };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                if (targetAPI.equals(API.EVENT)) {
                    EventRepresentation er = JSONBase.fromJSON(payload, EventRepresentation.class);
                    er = eventApi.create(er);
                    log.info("New event posted: {}", er);
                } else if (targetAPI.equals(API.ALARM)) {
                    AlarmRepresentation ar = JSONBase.fromJSON(payload, AlarmRepresentation.class);
                    ar = alarmApi.create(ar);
                    log.info("New alarm posted: {}", ar);
                } else if (targetAPI.equals(API.MEASUREMENT)) {
                    //MeasurementRepresentation mr = objectMapper.readValue(payload, MeasurementRepresentation.class);
                    MeasurementRepresentation mr = JSONBase.fromJSON(payload,MeasurementRepresentation.class);
                    mr = measurementApi.create(mr);
                    log.info("New measurement posted: {}", mr);
                } else {
                    log.error("Not existing API!");
                }
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {} {}", targetAPI, payload, s);
                errors[0] = "Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s;
            }
        });
        if (!errors[0].equals("")) {
            throw new ProcessingException(errors[0]);
        }
    }

    public void upsertDevice(String payload, String externalId, String externalIdType) throws ProcessingException {
        String[] errors = { "" };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                ExternalIDRepresentation extId = getExternalId(externalId, externalIdType);
                if (extId == null) {
                    // Device does not exist
                    ManagedObjectRepresentation mor = JSONBase.fromJSON(payload, ManagedObjectRepresentation.class);
                    // append external id to name
                    mor.setName(mor.getName());
                    mor.set(new IsDevice());
                    mor = inventoryApi.create(mor);
                    log.info("New device created: {}", mor);
                    ExternalIDRepresentation externalAgentId = createExternalID( mor,  externalId,
                            externalIdType);
                } else {
                    //Device exists - update needed
                    ManagedObjectRepresentation mor = JSONBase.fromJSON(payload, ManagedObjectRepresentation.class);
                    mor.setId(extId.getManagedObject().getId());
                    inventoryApi.update(mor);
                    log.info("Device updated: {}", mor);
                }

            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {}", payload, s);
                errors[0] = "Could not sent payload to c8y: " + payload + " " + s;
            }
        });
        if (!errors[0].equals("")) {
            throw new ProcessingException(errors[0]);
        }
    }

    public ManagedObjectRepresentation upsertDevice(String name, String type, String externalId,
            String externalIdType) {
        ManagedObjectRepresentation[] devices = { new ManagedObjectRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            devices[0].setName(name);
            devices[0].setType(type);
            devices[0].set(new IsDevice());
            devices[0] = inventoryApi.create(devices[0]);
            log.info("New device created with ID {}", devices[0].getId());
            ExternalIDRepresentation externalIdRep = createExternalID(devices[0], externalId, externalIdType);
            log.info("ExternalId created: {}", externalIdRep.getExternalId());
        });

        log.info("New device {} created with ID {}", devices[0], devices[0].getId());
        return devices[0];
    }

    public void saveMappings(List<Mapping> mappings) throws JsonProcessingException {
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            ManagedObjectRepresentation moUpdate = new ManagedObjectRepresentation();
            moUpdate.setId(mo.getId());
            moUpdate.setProperty(MQTT_MAPPING_FRAGMENT, mappings);
            inventoryApi.update(moUpdate);
            log.info("Updated Mapping after deletion!");
        });
    }

    public MQTTConfiguration setConfigurationActive(boolean b) {
        MQTTConfiguration[] mcr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            mcr[0] = configurationService.setConfigurationActive(b);
            log.info("Saved configuration");
        });
        return mcr[0];
    }

    public Mapping getMapping(Long id) {
        Mapping[] mr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            MappingsRepresentation mqttMo = objectMapper.convertValue(mo, MappingsRepresentation.class);
            log.debug("Found Mapping {}", mqttMo);
            mqttMo.getC8yMQTTMapping().forEach((m) -> {
                if (m.id == id) {
                    mr[0] = m;
                }
            });
            log.info("Found Mapping {}", mr[0]);
        });
        return mr[0];
    }

    public void sendStatusMapping(String type, Map<Long, MappingStatus> mappingStatus) {
        // avoid sending empty monitoring events
        if (mappingStatus.values().size() > 0) {
            log.debug("Sending monitoring: {}", mappingStatus.values().size());
            subscriptionsService.runForTenant(tenant, () -> {
                Map<String, Object> service = new HashMap<String, Object>();
                MappingStatus[] array = mappingStatus.values().toArray(new MappingStatus[0]);
                service.put("mapping_status", array);
                ManagedObjectRepresentation update = new ManagedObjectRepresentation();
                update.setId(agentMOR.getId());
                update.setAttrs(service);
                this.inventoryApi.update(update);
            });
        } else {
            log.info("Ignoring monitoring: {}", mappingStatus.values().size());
        }
    }

    public void sendStatusService(String type, ServiceStatus serviceStatus) {
        log.debug("Sending status configuration: {}", serviceStatus);
        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, String> entry = Map.of("status", serviceStatus.getStatus().name());
            Map<String, Object> service = new HashMap<String, Object>();
            service.put("service_status", entry);
            ManagedObjectRepresentation update = new ManagedObjectRepresentation();
            update.setId(agentMOR.getId());
            update.setAttrs(service);
            this.inventoryApi.update(update);
        });
    }
}
