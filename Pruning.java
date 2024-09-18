package Pruning;

import java.util.*;
import java.util.function.Function;

import lombok.Data;
import Common.EnvDynamics.EnvDynamics;
import Common.Device.Sub.InternalVariable;
import Common.Rule.Condition.Condition;
import Common.Rule.Rule;
import Common.Template.DevicePool;
import Common.Template.EnvPool;
import Core.Core;
import RuleChain.RuleChain;
import Specification.Specification;

@Data
public class Pruning {
    /**
     * Records the devices to be added.
     */
    static Set<String> usedDevice = new HashSet<>();

    /**
     * Records the rules to be added.
     */
    static Set<Integer> usedRule = new HashSet<>();

    /**
     * Records the rules to be added in sorted list.
     */
    static List<Integer> usedRuleSorted = new ArrayList<>();

    /**
     * Records the environmental variables to be added.
     */
    static Set<EnvDynamics> usedEnvDynamics = new HashSet<>();

    /**
     * Prunes the specification by identifying and adding necessary devices, rules, and environmental variables.
     * @param specification The specification to be pruned.
     */
    public static void prune(Specification specification) {
        for (Condition condition : specification.getIdToCond().values()) {
            addDeviceByEnv(condition);
            addDevice(condition.getDeviceName());
        }

        // Add used rules and devices to the corresponding collections.
        usedRuleSorted.forEach(i -> Core.getCoreInstance().getUsedRulesIndex().add(i));
        usedDevice.forEach(DevicePool::addDevice);
    }

    public static void prune(List<Specification> specifications) {
        for (Specification specification : specifications) {
            for (Condition condition : specification.getIdToCond().values()) {
                addDeviceByEnv(condition);
                addDevice(condition.getDeviceName());
            }
        }
        usedRuleSorted.sort(Integer::compareTo);
        usedRuleSorted.forEach(i -> Core.getCoreInstance().getUsedRulesIndex().add(i));
        usedDevice.forEach(DevicePool::addDevice);
    }

    /**
     * Adds the devices driven by the given environmental variable to the final set.
     * @param condition The condition containing the environmental variable.
     */
    private static void addDeviceByEnv(Condition condition) {
        String varName = condition.getDeviceVar();
        String deviceId = condition.getDeviceName();

        // If the variable detected belongs to the env-dynamics, record it.
        if (EnvPool.constantDynamics.contains(varName)) {
            InternalVariable internalVariable;
            if ((internalVariable = DevicePool.findDevice(deviceId).findInternalVariable(varName)) != null) {
                EnvPool.addEnvDynamics(new EnvDynamics(internalVariable));
            }
            List<Rule> ruleList = Core.getCoreInstance().getRules();
            for (int i = 0; i < ruleList.size(); i++) {
                Rule rule = ruleList.get(i);
                for (Condition cond : rule.getConditions()) {
                    if (cond.getDeviceVar().equals(varName)) {
                        if (!usedRule.contains(i)) {
                            usedRule.add(i);
                            usedRuleSorted.add(i);
                        }
                        addDevice(rule.getAction().getDeviceName()); // Recursive addition
                    }
                }
            }
        }
    }

    /**
     * Adds the device connected to target devices by rules.
     * @param deviceName The name of the device to be added.
     */
    private static void addDevice(String deviceName) {
        if (!usedDevice.contains(deviceName)) {
            usedDevice.add(deviceName);
            for (Condition subCondition : RuleChain.ruleChain.keySet()) {
                if (subCondition.getDeviceName().equals(deviceName)) {
                    for (Rule rule : RuleChain.ruleChain.get(subCondition)) {
                        recursiveAdd(rule);
                    }
                }
            }
        }
    }

    /**
     * Recursively adds devices and rules associated with the given rule.
     * @param rule The rule to be recursively added.
     */
    public static void recursiveAdd(Rule rule) {
        int index = Core.getCoreInstance().getRules().indexOf(rule);
        if (index >= 0 && !usedRule.contains(index)) {
            usedRule.add(index);
            usedRuleSorted.add(index);
        }
        usedDevice.add(rule.getAction().getDeviceName());
        // Recursively add devices and environmental variables associated with each condition in the rule
        rule.getConditions().forEach(i->{addDeviceByEnv(i); addDevice(i.getDeviceName());});
    }

    /**
     * Clears the collections of used devices and rules.
     */
    public static void clear() {
        usedDevice.clear();
        usedRule.clear();
        usedRuleSorted.clear();
    }
}
