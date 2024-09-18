package StatePath;

import java.util.*;
import lombok.Data;
import static Common.Enums.Compare.compare;
import static Common.Enums.Type.DeviceOperationType.*;
import static Util.StringUtils.formatString;
import Common.EnvDynamics.EnvDynamics;
import Common.Device.Sub.Trigger;
import Common.Enums.Type.DeviceOperationType;
import Common.Enums.Type.EnvOperationType;
import Common.Enums.Type.OperationType;
import Common.Template.DevicePool;
import Common.Enums.Compare;
import Common.Device.Device;
import Common.Template.EnvPool;
import Common.Rule.Condition.Condition;
import StatePath.subState.DeviceInstance;
import StatePath.subState.EnvInstance;

@Data
public class StateNode implements Cloneable{
    /**
     * The device instances in the state node.
     */
    private Map<String, DeviceInstance> deviceInstances;

    /**
     * The environment variable instances in the state node.
     */
    private Map<String, EnvInstance> envInstances;

    /**
     * Records the nodes that can be reached in the current state and graph.
     */
    private String graphVer;

    /**
     * Initializes the state node based on the device pool and environment variable pool.
     */
    public StateNode() {
        deviceInstances = new HashMap<>();
        envInstances = new HashMap<>();
        for (Device device : DevicePool.usedDevices.values()) {
            for (int i : device.getCountList()) {
                deviceInstances.put(device.getName() + i, new DeviceInstance(device, i));
            }
        }
        // Add key-value pairs contained in environment variables.
        Set<EnvDynamics> dynamicsSet = EnvPool.envDynamicsSet;
        for (EnvDynamics envDynamics : dynamicsSet) {
            envInstances.put(formatString(envDynamics.getVariableName()), new EnvInstance(envDynamics));
        }
    }

    /**
     * Searches for the value corresponding to the given variable name and whether to check trust or privacy.
     * @param id The input variable name.
     * @param variable The input variable name.
     * @param checkTrust Whether to check trust.
     * @param checkPrivacy Whether to check privacy.
     * @return The value retrieved from the search.
     */
    public String findVar(String id, String variable, Boolean checkTrust, Boolean checkPrivacy) {
        DeviceInstance tempVar;
        String val = null;
        variable = formatString(variable);
        if ((tempVar = findDeviceInstance(id)) != null) {
            if (checkTrust | checkPrivacy) {
                if (checkTrust) {
                    val = tempVar.findTrust(variable);
                } else {
                    val = tempVar.findPrivacy(variable);
                }
            } else {
                val = findVarSub(variable, tempVar);
            }
        }
        return val;
    }

    /**
     * Searches for detailed information about the device.
     */
    public String findVarSub(String variable, DeviceInstance deviceInstance) {
        String val;
        // The first three are the generalized attributes of the device, and the default is the value of its internal state or attribute.
        // The trust or privacy of these values has been processed at the higher level (findTrust).
        switch (variable) {
            case TRUST:
                val = deviceInstance.getTrust();
                break;
            case PRIVACY:
                val = deviceInstance.getPrivacy();
                break;
            case ATTACK:
                val = String.valueOf(deviceInstance.isAttacked());
                break;
            default:
                if (findEnvInstance(variable) != null) {
                    val = findEnvInstance(variable).getVariableVal();
                } else if ((val = deviceInstance.findVarInModes(variable)) == null){
                    val = deviceInstance.findVarInInternalVars(variable);
                }
        }
        return val;
    }

    /**
     * Find the device instance based on the device ID.
     * @param variableName The device ID.
     * @return The device instance.
     */
    public DeviceInstance findDeviceInstance(String variableName) {
       return this.deviceInstances.get(variableName);
    }


    /**
     * Find the environment variable instance based on the variable name.
     * @param variableName The variable name.
     * @return The environment variable instance.
     */
    public EnvInstance findEnvInstance(String variableName) {
        return this.envInstances.get(formatString(variableName));
    }

    /**
     * Set the value of a variable.
     * @param id The variable name to be modified.
     * @param op The operation type.
     * @param variableVal The new value of the variable.
     */
    public void setVal(String id, OperationType op, String variableVal) {
        if (op instanceof EnvOperationType) {
            setDynamicsVar(findEnvInstance(id), (EnvOperationType)op, variableVal);
        } else if (op instanceof DeviceOperationType){
            setDeviceVar(deviceInstances.get(id), (DeviceOperationType)op, variableVal);
        }
    }

    /**
     * Set the value of a dynamic variable.
     */
    public void setDynamicsVar(EnvInstance envInstance, EnvOperationType op, String variableVal) {
        switch (op) {
            case ChangeRate:
                envInstance.setChangeRate(variableVal);
                break;
            case Value:
                envInstance.setVariableVal(variableVal);
                break;
            default:
                // In case the operation or envInstance do not exist.
        }
    }

    /**
     * Set the value of a device attribute.
     */
    public void setDeviceVar(DeviceInstance deviceInstance, DeviceOperationType op, String variableVal) {
        switch (op) {
            case WorkingState:
                deviceInstance.setWorkingState(variableVal);
                return;
            case API:
                deviceInstance.setStateByAPI(variableVal);
                return;
            case Trust:
                deviceInstance.setTrust(variableVal);
                return;
            case Privacy:
                deviceInstance.setPrivacy(variableVal);
                return;
            case Attack:
                setDeviceAttacked(deviceInstance, variableVal);
                return;
            default:
                // In case the operation or deviceInstance do not exist.
        }
    }

    /**
     * Set the detailed value concerning trust or privacy of the devices' states.
     */
    public void setStateDetail(String id, String variableName, DeviceOperationType op, String variableVal) {
        if (deviceInstances.containsKey(id)) {
            DeviceInstance deviceInstance = deviceInstances.get(id);
            String state = DevicePool.findEndState(deviceInstance.getDeviceId(), variableName);
            if (state != null && !state.isEmpty()) {
                switch (op) {
                    case Trust:
                        deviceInstance.changeStateTrust(state, variableVal);
                        break;
                    case Privacy:
                        deviceInstance.changeStatePrivacy(state, variableVal);
                        break;
                    default:
                        // In case the operation or variable do not exist.
                }
            }
        }
    }

    /**
     * When the device is attacked, change the relevant attributes.
     */
    private void setDeviceAttacked(DeviceInstance deviceInstance, String variableVal) {
        deviceInstance.setAttacked(variableVal);
    }

    /**
     * Receives a condition and makes a judgment based on its contents and the current state.
     */
    public boolean judgeCond(Condition condition) {
        String targetId = condition.getDeviceName();
        String varName = condition.getDeviceVar();
        String varVal = condition.getVariableVal();
        Compare compare = condition.getCompare();
        String ans = findVar(targetId, varName, condition.getCheckTrust(), condition.getCheckPrivacy());
        if (ans != null && !ans.isEmpty()) {
            return compare(ans, varVal, compare);
        } else {
            // illegal input
            return false;
        }
    }

    /**
     * Determine whether two consecutive state nodes meet the condition.
     * @param condition The condition to be met.
     * @param preNode The previous state node.
     */
    public boolean judgeContinueCond(Condition condition, StateNode preNode) {
        String targetId = condition.getDeviceName();
        String varName = condition.getDeviceVar();
        String varVal = condition.getVariableVal();
        Compare compare = condition.getCompare();
        String ans = findVar(targetId, varName, condition.getCheckTrust(), condition.getCheckPrivacy());

        if (ans != null && !ans.isEmpty()) {
            String ansPre = preNode.findVar(targetId, varName, condition.getCheckTrust(), condition.getCheckPrivacy());
            boolean curCom = compare(ans, varVal, compare);
            if (curCom) {
                return true;
            } else {
                try {
                    return judgeCondEqual(ans, ansPre, varVal, compare);
                } catch (Exception ignored) {
                    // Avoid non-double types, in which case continuous judgment is not required
                }
            }
        }
        return false;
    }

    /**
     * Determine whether the value between two points in a continuous node can reach, considering only double types.
     */
    private boolean judgeCondEqual(String val, String valPre, String varVal, Compare compare) {
        double dCur = Double.parseDouble(val);
        double dPre = Double.parseDouble(valPre);
        double dTarget = Double.parseDouble(varVal);
        return (compare.equals(Compare.EQUAL)
                && (dCur > dTarget && dPre < dTarget || dCur < dTarget && dPre > dTarget));
    }

    /**
     * Determine whether the trigger condition is met.
     * @param deviceInstance The device instance corresponding to the trigger.
     * @param trigger The trigger condition attached to the transition.
     * @return Whether the trigger is triggered.
     */
    public boolean judgeTrigger(DeviceInstance deviceInstance, Trigger trigger) {
        String attribution = trigger.getAttribute();
        String target = trigger.getValue();
        String val;
        Compare relation = Compare.value(trigger.getRelation());
        if (envInstances.containsKey(attribution.toLowerCase()))
            val = findEnvInstance(attribution).getVariableVal();
        else
            val = deviceInstance.findVarInModes(attribution);
        return compare(target, val, relation);
    }

    /**
     * Returns a clone of the next state node.
     */
    public StateNode nextClone() {
        try {
            StateNode clone = (StateNode) super.clone();
            preClone(clone);
            for (String deviceId : deviceInstances.keySet())
                clone.deviceInstances.put(deviceId, deviceInstances.get(deviceId).nextClone());
            for (String dynamicsId : envInstances.keySet())
                clone.envInstances.put(dynamicsId, envInstances.get(dynamicsId).nextClone());
            return clone;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Object clone() {
        try {
            StateNode clone = (StateNode) super.clone();
            preClone(clone);
            for (String deviceId : deviceInstances.keySet())
                clone.deviceInstances.put(deviceId, (DeviceInstance) deviceInstances.get(deviceId).clone());
            for (String dynamicsId : envInstances.keySet())
                clone.envInstances.put(dynamicsId, (EnvInstance) envInstances.get(dynamicsId).clone());
            return clone;
        } catch (Exception e) {
            return null;
        }
    }

    private void preClone(StateNode clone) {
        clone.deviceInstances = new HashMap<>();
        clone.envInstances = new HashMap<>();
        clone.graphVer = this.graphVer;
    }
}
