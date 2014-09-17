// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// Automatically generated by addcopyright.py at 01/29/2013
package com.cloud.baremetal.networkservice;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.log4j.Logger;

import org.apache.cloudstack.api.AddBaremetalKickStartPxeCmd;
import org.apache.cloudstack.api.AddBaremetalPxeCmd;
import org.apache.cloudstack.api.ListBaremetalPxeServersCmd;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.baremetal.IpmISetBootDevCommand;
import com.cloud.agent.api.baremetal.IpmISetBootDevCommand.BootDev;
import com.cloud.baremetal.database.BaremetalPxeDao;
import com.cloud.baremetal.database.BaremetalPxeVO;
import com.cloud.baremetal.networkservice.BaremetalPxeManager.BaremetalPxeType;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.guru.ControlNetworkGuru;
import com.cloud.network.router.VirtualRouter;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ServerResource;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;

@Local(value = BaremetalPxeService.class)
public class BaremetalKickStartServiceImpl extends BareMetalPxeServiceBase implements BaremetalPxeService {
    private static final Logger s_logger = Logger.getLogger(BaremetalKickStartServiceImpl.class);
    @Inject
    ResourceManager _resourceMgr;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    PhysicalNetworkServiceProviderDao _physicalNetworkServiceProviderDao;
    @Inject
    HostDetailsDao _hostDetailsDao;
    @Inject
    BaremetalPxeDao _pxeDao;
    @Inject
    NetworkDao _nwDao;
    @Inject
    VMTemplateDao _tmpDao;
    @Inject
    DomainRouterDao _routerDao;
    @Inject
    NicDao _nicDao;
    @Inject
    ConfigurationDao _configDao;

    private DomainRouterVO getVirtualRouter(Network network) {
        List<DomainRouterVO> routers = _routerDao.listByNetworkAndRole(network.getId(), VirtualRouter.Role.VIRTUAL_ROUTER);

        if (routers.isEmpty()) {
            throw new CloudRuntimeException(String.format("cannot find any running virtual router on network[id:%s, uuid:%s]", network.getId(), network.getUuid()));
        }

        if (routers.size() > 1) {
            throw new CloudRuntimeException(String.format("baremetal hasn't supported redundant router yet"));
        }

        DomainRouterVO vr = routers.get(0);
        if (!Hypervisor.HypervisorType.VMware.equals(vr.getHypervisorType())) {
            throw new CloudRuntimeException(String.format("baremetal only support vmware virtual router, but get %s", vr.getHypervisorType()));
        }

        return vr;
    }

    private List<String> parseKickstartUrl(VirtualMachineProfile profile) {
        String tpl = profile.getTemplate().getUrl();
        assert tpl != null : "How can a null template get here!!!";
        String[] tpls = tpl.split(";");
        CloudRuntimeException err =
                new CloudRuntimeException(
                        String.format(
                                "template url[%s] is not correctly encoded. it must be in format of ks=http_link_to_kickstartfile;kernel=nfs_path_to_pxe_kernel;initrd=nfs_path_to_pxe_initrd",
                                tpl));
        if (tpls.length != 3) {
            throw err;
        }

        String ks = null;
        String kernel = null;
        String initrd = null;

        for (String t : tpls) {
            String[] kv = t.split("=");
            if (kv.length != 2) {
                throw err;
            }
            if (kv[0].equals("ks")) {
                ks = kv[1];
            } else if (kv[0].equals("kernel")) {
                kernel = kv[1];
            } else if (kv[0].equals("initrd")) {
                initrd = kv[1];
            } else {
                throw err;
            }
        }

        return Arrays.asList(ks, kernel, initrd);
    }

    private File getSystemVMKeyFile() {
        URL url = this.getClass().getClassLoader().getResource("scripts/vm/systemvm/id_rsa.cloud");
        File keyFile = null;
        if (url != null) {
            keyFile = new File(url.getPath());
        }
        if (keyFile == null || !keyFile.canRead()) {
            s_logger.error("Unable to locate id_rsa.cloud");
            return null;
        }
        return keyFile;
    }

    private boolean preparePxeInBasicZone(VirtualMachineProfile profile, NicProfile nic, DeployDestination dest, ReservationContext context) throws AgentUnavailableException, OperationTimedoutException {
        NetworkVO nwVO = _nwDao.findById(nic.getNetworkId());
        QueryBuilder<BaremetalPxeVO> sc = QueryBuilder.create(BaremetalPxeVO.class);
        sc.and(sc.entity().getDeviceType(), Op.EQ, BaremetalPxeType.KICK_START.toString());
        sc.and(sc.entity().getPhysicalNetworkId(), Op.EQ, nwVO.getPhysicalNetworkId());
        BaremetalPxeVO pxeVo = sc.find();
        if (pxeVo == null) {
            throw new CloudRuntimeException("No kickstart PXE server found in pod: " + dest.getPod().getId() + ", you need to add it before starting VM");
        }
        VMTemplateVO template = _tmpDao.findById(profile.getTemplateId());
        List<String> tuple =  parseKickstartUrl(profile);

        String ks = tuple.get(0);
        String kernel = tuple.get(1);
        String initrd = tuple.get(2);

        PrepareKickstartPxeServerCommand cmd = new PrepareKickstartPxeServerCommand();
        cmd.setKsFile(ks);
        cmd.setInitrd(initrd);
        cmd.setKernel(kernel);
        cmd.setMac(nic.getMacAddress());
        cmd.setTemplateUuid(template.getUuid());
        Answer aws = _agentMgr.send(pxeVo.getHostId(), cmd);
        if (!aws.getResult()) {
            s_logger.warn("Unable to set host: " + dest.getHost().getId() + " to PXE boot because " + aws.getDetails());
            return false;
        }

        return true;
    }

    private boolean preparePxeInAdvancedZone(VirtualMachineProfile profile, NicProfile nic, Network network, DeployDestination dest, ReservationContext context) throws Exception {
        DomainRouterVO vr = getVirtualRouter(network);
        List<NicVO> nics = _nicDao.listByVmId(vr.getId());
        NicVO mgmtNic = null;
        for (NicVO nicvo : nics) {
            if (ControlNetworkGuru.class.getSimpleName().equals(nicvo.getReserver())) {
                mgmtNic = nicvo;
                break;
            }
        }

        if (mgmtNic == null) {
            throw new CloudRuntimeException(String.format("cannot find management nic on virtual router[id:%s]", vr.getId()));
        }

        List<String> tuple =  parseKickstartUrl(profile);
        String cmd =  String.format("/usr/bin/prepare_pxe.sh %s %s %s %s %s %s", tuple.get(1), tuple.get(2), profile.getTemplate().getUuid(),
                String.format("01-%s", nic.getMacAddress().replaceAll(":", "-")).toLowerCase(), tuple.get(0), nic.getMacAddress().toLowerCase());
        s_logger.debug(String.format("prepare pxe on virtual router[ip:%s], cmd: %s", mgmtNic.getIp4Address(), cmd));
        Pair<Boolean, String> ret = SshHelper.sshExecute(mgmtNic.getIp4Address(), 3922, "root", getSystemVMKeyFile(), null, cmd);
        if (!ret.first()) {
            throw new CloudRuntimeException(String.format("failed preparing PXE in virtual router[id:%s], because %s", vr.getId(), ret.second()));
        }

        //String internalServerIp = _configDao.getValue(Config.BaremetalInternalStorageServer.key());
        String internalServerIp = "10.223.110.231";
        cmd = String.format("/usr/bin/baremetal_snat.sh %s %s %s", mgmtNic.getIp4Address(), internalServerIp, mgmtNic.getGateway());
        s_logger.debug(String.format("prepare SNAT on virtual router[ip:%s], cmd: %s", mgmtNic.getIp4Address(), cmd));
        ret = SshHelper.sshExecute(mgmtNic.getIp4Address(), 3922, "root", getSystemVMKeyFile(), null, cmd);
        if (!ret.first()) {
            throw new CloudRuntimeException(String.format("failed preparing PXE in virtual router[id:%s], because %s", vr.getId(), ret.second()));
        }

        return true;
    }

    @Override
    public boolean prepare(VirtualMachineProfile profile, NicProfile nic, Network network, DeployDestination dest, ReservationContext context) {
        try {
            if (DataCenter.NetworkType.Basic.equals(dest.getDataCenter().getNetworkType())) {
                if (!preparePxeInBasicZone(profile, nic, dest, context)) {
                    return false;
                }
            } else {
                if (!preparePxeInAdvancedZone(profile, nic, network, dest, context)) {
                    return false;
                }
            }

            IpmISetBootDevCommand bootCmd = new IpmISetBootDevCommand(BootDev.pxe);
            Answer aws = _agentMgr.send(dest.getHost().getId(), bootCmd);
            if (!aws.getResult()) {
                s_logger.warn("Unable to set host: " + dest.getHost().getId() + " to PXE boot because " + aws.getDetails());
            }

            return aws.getResult();
        } catch (Exception e) {
            s_logger.warn("Cannot prepare PXE server", e);
            return false;
        }
    }

    @Override
    public boolean prepareCreateTemplate(Long pxeServerId, UserVm vm, String templateUrl) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    @DB
    public BaremetalPxeVO addPxeServer(AddBaremetalPxeCmd cmd) {
        AddBaremetalKickStartPxeCmd kcmd = (AddBaremetalKickStartPxeCmd)cmd;
        PhysicalNetworkVO pNetwork = null;
        long zoneId;

        if (cmd.getPhysicalNetworkId() == null || cmd.getUrl() == null || cmd.getUsername() == null || cmd.getPassword() == null) {
            throw new IllegalArgumentException("At least one of the required parameters(physical network id, url, username, password) is null");
        }

        pNetwork = _physicalNetworkDao.findById(cmd.getPhysicalNetworkId());
        if (pNetwork == null) {
            throw new IllegalArgumentException("Could not find phyical network with ID: " + cmd.getPhysicalNetworkId());
        }
        zoneId = pNetwork.getDataCenterId();

        PhysicalNetworkServiceProviderVO ntwkSvcProvider =
                _physicalNetworkServiceProviderDao.findByServiceProvider(pNetwork.getId(), BaremetalPxeManager.BAREMETAL_PXE_SERVICE_PROVIDER.getName());
        if (ntwkSvcProvider == null) {
            throw new CloudRuntimeException("Network Service Provider: " + BaremetalPxeManager.BAREMETAL_PXE_SERVICE_PROVIDER.getName() +
                    " is not enabled in the physical network: " + cmd.getPhysicalNetworkId() + "to add this device");
        } else if (ntwkSvcProvider.getState() == PhysicalNetworkServiceProvider.State.Shutdown) {
            throw new CloudRuntimeException("Network Service Provider: " + ntwkSvcProvider.getProviderName() + " is in shutdown state in the physical network: " +
                    cmd.getPhysicalNetworkId() + "to add this device");
        }

        List<HostVO> pxes = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.BaremetalPxe, zoneId);
        if (!pxes.isEmpty()) {
            throw new IllegalArgumentException("Already had a PXE server zone: " + zoneId);
        }

        String tftpDir = kcmd.getTftpDir();
        if (tftpDir == null) {
            throw new IllegalArgumentException("No TFTP directory specified");
        }

        URI uri;
        try {
            uri = new URI(cmd.getUrl());
        } catch (Exception e) {
            s_logger.debug(e);
            throw new IllegalArgumentException(e.getMessage());
        }
        String ipAddress = uri.getHost();
        if (ipAddress == null) {
            ipAddress = cmd.getUrl();
        }

        String guid = getPxeServerGuid(Long.toString(zoneId), BaremetalPxeType.KICK_START.toString(), ipAddress);

        ServerResource resource = null;
        Map params = new HashMap<String, String>();
        params.put(BaremetalPxeService.PXE_PARAM_ZONE, Long.toString(zoneId));
        params.put(BaremetalPxeService.PXE_PARAM_IP, ipAddress);
        params.put(BaremetalPxeService.PXE_PARAM_USERNAME, cmd.getUsername());
        params.put(BaremetalPxeService.PXE_PARAM_PASSWORD, cmd.getPassword());
        params.put(BaremetalPxeService.PXE_PARAM_TFTP_DIR, tftpDir);
        params.put(BaremetalPxeService.PXE_PARAM_GUID, guid);
        resource = new BaremetalKickStartPxeResource();
        try {
            resource.configure("KickStart PXE resource", params);
        } catch (Exception e) {
            throw new CloudRuntimeException(e.getMessage(), e);
        }

        Host pxeServer = _resourceMgr.addHost(zoneId, resource, Host.Type.BaremetalPxe, params);
        if (pxeServer == null) {
            throw new CloudRuntimeException("Cannot add PXE server as a host");
        }

        BaremetalPxeVO vo = new BaremetalPxeVO();
        vo.setHostId(pxeServer.getId());
        vo.setNetworkServiceProviderId(ntwkSvcProvider.getId());
        vo.setPhysicalNetworkId(kcmd.getPhysicalNetworkId());
        vo.setDeviceType(BaremetalPxeType.KICK_START.toString());
        _pxeDao.persist(vo);
        return vo;
    }

    @Override
    public BaremetalPxeResponse getApiResponse(BaremetalPxeVO vo) {
        BaremetalPxeResponse response = new BaremetalPxeResponse();
        response.setId(vo.getUuid());
        HostVO host = _hostDao.findById(vo.getHostId());
        response.setUrl(host.getPrivateIpAddress());
        PhysicalNetworkServiceProviderVO providerVO = _physicalNetworkServiceProviderDao.findById(vo.getNetworkServiceProviderId());
        response.setPhysicalNetworkId(providerVO.getUuid());
        PhysicalNetworkVO nwVO = _physicalNetworkDao.findById(vo.getPhysicalNetworkId());
        response.setPhysicalNetworkId(nwVO.getUuid());
        response.setObjectName("baremetalpxeserver");
        return response;
    }

    @Override
    public List<BaremetalPxeResponse> listPxeServers(ListBaremetalPxeServersCmd cmd) {
        List<BaremetalPxeResponse> responses = new ArrayList<BaremetalPxeResponse>();
        if (cmd.getId() != null) {
            BaremetalPxeVO vo = _pxeDao.findById(cmd.getId());
            responses.add(getApiResponse(vo));
            return responses;
        }

        List<BaremetalPxeVO> vos = _pxeDao.listAll();
        for (BaremetalPxeVO vo : vos) {
            responses.add(getApiResponse(vo));
        }
        return responses;
    }

    @Override
    public String getPxeServiceType() {
        return BaremetalPxeManager.BaremetalPxeType.KICK_START.toString();
    }

}
