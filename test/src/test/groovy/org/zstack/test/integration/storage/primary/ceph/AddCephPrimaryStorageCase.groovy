package org.zstack.test.integration.storage.primary.ceph

import org.springframework.http.HttpEntity
import org.zstack.core.cloudbus.CloudBus
import org.zstack.core.db.DatabaseFacade
import org.zstack.core.db.Q
import org.zstack.sdk.AddCephPrimaryStorageAction
import org.zstack.storage.ceph.primary.CephPrimaryStorageMonBase
import org.zstack.storage.ceph.primary.CephPrimaryStorageMonVO
import org.zstack.test.integration.storage.StorageTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.HttpError
import org.zstack.testlib.SubCase
import org.zstack.testlib.Test
import org.zstack.utils.Utils
import org.zstack.utils.logging.CLogger

class AddCephPrimaryStorageCase extends SubCase {
    private final static CLogger logger = Utils.getLogger(AddCephPrimaryStorageCase.class)

    def DOC = """
use:
1. add ceph primary storage. cephPSVO and monVOs would be created.
2. hook internal message[ConnectPrimaryStorageMsg], save and reply with failure. cephPSVO would be cleaned.
3. resend saved message, trigger NPE
"""


    EnvSpec env
    DatabaseFacade dbf
    CloudBus bus


    @Override
    void clean() {
        env.delete()
    }

    @Override
    void setup() {
        useSpring(StorageTest.springSpec)
    }

    @Override
    void environment() {
        env = Test.makeEnv {
            zone {
                name = "zone1"
                cluster {
                    name = "cluster1"
                    hypervisorType = "KVM"

                    kvm {
                        name = "host1"
                        managementIp = "localhost"
                        username = "root"
                        password = "password"
                        usedMem = 1000
                        totalCpu = 10
                    }

                    attachL2Network("l2")
                }

                l2NoVlanNetwork {
                    name = "l2"
                    physicalInterface = "eth0"

                    l3Network {
                        name = "l3"



                        ip {
                            startIp = "192.168.100.10"
                            endIp = "192.168.100.100"
                            netmask = "255.255.255.0"
                            gateway = "192.168.100.1"
                        }
                    }
                }

            }
        }
    }

    void testAfterAddCephPrimaryStorageTimeoutStillConnect() {
        env.simulator(CephPrimaryStorageMonBase.ECHO_PATH) { HttpEntity<String> entity, EnvSpec spec ->
            throw new HttpError(404, "on purpose")
        }

        AddCephPrimaryStorageAction action = new AddCephPrimaryStorageAction()
        action.name = "ceph-primary-new"
        action.monUrls = ["root:password@localhost"]
        action.rootVolumePoolName = "rootPool"
        action.dataVolumePoolName = "dataPool"
        action.imageCachePoolName = "cachePool"
        action.zoneUuid = env.inventoryByName("zone1").uuid
        action.sessionId = adminSession()
        action.call()

        // wait echo finished
        retryInSecs{
            assert Q.New(CephPrimaryStorageMonVO.class).count() == 0l: "after failed to add cephPS, all monVO should be removed, but some left"
        }
    }

    @Override
    void test() {
        dbf = bean(DatabaseFacade.class)
        bus = bean(CloudBus.class)

        env.create {
            testAfterAddCephPrimaryStorageTimeoutStillConnect()
        }
    }
}
