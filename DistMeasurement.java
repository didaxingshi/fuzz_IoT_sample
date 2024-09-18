package Mutate;

import java.util.*;
import Common.EnvDynamics.EnvDynamics;
import Common.Enums.Compare;
import Common.Rule.Condition.Condition;
import Common.Rule.Rule;
import Core.Core;
import RuleChain.RuleChain;
import Specification.Graph.Graph;
import Specification.Specification;
import StatePath.StateNode;
import StatePath.StatePath;
import StatePath.subState.EnvInstance;

public class DistMeasurement {
    /**
     * Number of layers for mutation exploration, including the first layer for implementation convenience.
     */
    static int DETECTION_LAYER_NUM = 0;

    /**
     * The first layer shall be coped particularly.
     */
    static final int BASE_LAYER = 1;

    /**
     * The map of power in different layers.
     */
    private final List<Double> powerMap = new ArrayList<>();

    private Specification specification;

    /**
     * Singleton design.
     */
    private static final DistMeasurement distMeasurement = new DistMeasurement();

    public static DistMeasurement getInstance() {
        return distMeasurement;
    }

    /**
     * Initialize the weight map.
     * layer    1       2       3       4       5
     * weight 16/31   8/31    4/31    2/31    1/31  total = 1
     */
    private DistMeasurement() {
        double total = 0;
        for (int i = 0; i < DETECTION_LAYER_NUM; i++) {
            powerMap.add(Math.pow(2, i));
            total += Math.pow(2, i);
        }
        powerMap.add(total);
    }

    /**
     * Elects the best state path for mutation.
     */
    public StatePath pathVoter(Specification specification) {
        StatePath bestPath = null;
        List<StatePath> statePaths = Core.getCoreInstance().getStatePaths();
        this.specification = specification;
        double maxDist = Double.MAX_VALUE;
        for (StatePath statePath : statePaths) {
            double pathDist = calcPathDist(statePath);
            if (pathDist < maxDist) {
                maxDist = pathDist;
                bestPath = statePath;
            }
        }
        if (new Random().nextDouble() > 0.8) // Partial random weighting
            bestPath = statePaths.get(new Random().nextInt(statePaths.size()));

        return bestPath;
    }

    /**
     * Calculate the distance of the given statePath.
     * @param statePath The statePath required to measure.
     * @return The distance of the path.
     */
    private double calcPathDist(StatePath statePath) {
        double res = Double.MAX_VALUE;
        for (StateNode stateNode : statePath.getStateNodes()) {
            res = Math.min(calcNodeDist(stateNode, stateNode.getGraphVer()), res);
        }
        return res;
    }

    /**
     * Calculate the distance of the given stateNode to false node.
     * @param stateNode The statePath required to measure.
     * @return The distance of the path.
     */
    private double calcNodeDist(StateNode stateNode, String graphVer) {
        Graph monitorGraph = specification.getMonitorGraph();
        HashMap<String, String> labelNode = monitorGraph.findEdge(graphVer);
        int nodeDist = monitorGraph.getVerDist(graphVer); // Current distance in the LTL graph
        double condDist = 0;
        for (String label : labelNode.keySet()) {
            if (monitorGraph.getVerDist(labelNode.get(label)) == (nodeDist - 1)) {
                // Take the maximum value of distances for different labels (to get the closest distance)
                double accurate = accurateDist(label, stateNode);
                condDist = Math.max(condDist, accurate);
            }
        }
        return nodeDist - condDist;
    }

    /**
     * Performs fine-grained quantization measurement.
     * @param label The condition label (e.g., a0 && a1)
     * @param stateNode The state node
     */
    public double accurateDist(String label, StateNode stateNode) {
        if (DETECTION_LAYER_NUM == 0) {
            return 0;
        }
        List<Condition> conditions = new ArrayList<>();
        String[] singleLabels = label.split("&&");
        for (String singleLabel : singleLabels) {    // Conditions involved in the label, connected by 'and'
            if (specification.getIdToCond().get(singleLabel) != null) {
                conditions.add(specification.getIdToCond().get(singleLabel));
            }
        }
        List<Double> firstLevel = new ArrayList<>();
        List<Double> subCondDist = new ArrayList<>();
        for (Condition condition : conditions) { // && connection
            double tempDist = conditionDist(condition, stateNode); // Calculate the length of the individual condition
            firstLevel.add(tempDist);
            if (DETECTION_LAYER_NUM > BASE_LAYER) {
                subCondDist.add(seekRuleChain(condition, stateNode, BASE_LAYER)); // dfs
            }
        }
        return calcByWeight(firstLevel, subCondDist);
    }

    /**
     * Performs weighted calculation based on the known lengths of the first layer and the remaining layers.
     */
    private double calcByWeight(List<Double> firstLevel, List<Double> subCondDist) {
        OptionalDouble firDist = firstLevel.stream().mapToDouble(i -> i).average(); // to the & logic, we use average
        if (!firDist.isPresent()) {
            return 0;
        } else {
            double firDistPowered = firDist.getAsDouble() * weightCalc(BASE_LAYER);
            if (!subCondDist.isEmpty()) {
                OptionalDouble leftDist = subCondDist.stream().mapToDouble(i -> i).average();
                if (leftDist.isPresent()) {
                    return leftDist.getAsDouble() + firDistPowered;
                }
            }
            return firDistPowered;
        }
    }

    /**
     * Detect the rule chain for more detailed distance with a specific condition given.
     * @param condition Given condition for calculating the specific distance from current situation to fulfilling the condition.
     * @param stateNode Given stateNode which offers the necessary information of env-dynamics and devices.
     * @param cur_pos Current depth of detection.
     * @return The specific distance from current situation to fulfilling the condition through detection driven by ruleChain.
     */
    private double seekRuleChain(Condition condition, StateNode stateNode, int cur_pos) {
        if (RuleChain.ruleChain.containsKey(condition)) { // Starting from a single condition, find predecessors in the chain
            List<Rule> rules = RuleChain.ruleChain.get(condition); // A series of predecessors connected by ||
            OptionalDouble setDistOpt = rules.stream().map(i -> seekDown(i, cur_pos + 1, stateNode)).mapToDouble(i -> i).max(); // Recursively apply for each rule, take the maximum value as the final result
            return setDistOpt.isPresent() ? setDistOpt.getAsDouble() : 0;
        } else {
            return 0;
        }
    }

    /**
     * Detect the rule chain for more detailed distance.
     * @param rule The rule to be detected.
     * @param depthCount Current depth of detection.
     * @param stateNode Given stateNode which offers the necessary information of env-dynamics and devices.
     * @return The specific distance from current situation to fulfilling the rule's conditions.
     */
    public double seekDown(Rule rule, int depthCount, StateNode stateNode) {
        if (depthCount > BASE_LAYER) {
            return 0;
        }
        double finalDist;
        OptionalDouble levelDist = rule.getConditions()
                .stream().map(i -> conditionDist(i, stateNode)).mapToDouble(i -> i).average(); // in current layer, rule's "and" connections have to be coped with average
        finalDist = levelDist.isPresent() ? levelDist.getAsDouble() * weightCalc(depthCount) : 0; // weight

        double subDist = 0;
        if (!rule.getConditions().isEmpty()) {
            List<Double> conditionDist = new ArrayList<>();
            for (Condition condition : rule.getConditions()) {
                // Continue the predecessor DFS, similar to the operation when calculating the first layer of the entire node, where the condition is all the conditions in the rule connected by &&
                double setDist = seekRuleChain(condition, stateNode, depthCount);
                if (setDist > 0) // If it's 0, it means there are no relevant conditions
                    conditionDist.add(setDist);
            }
            OptionalDouble subDistOpt = conditionDist.stream().mapToDouble(i -> i).average(); // "&&" with average
            subDist = subDistOpt.isPresent() ? subDistOpt.getAsDouble() : 0;
        }

        return finalDist + subDist;
    }

    /**
     * Measures the distance for a specific condition within the target state node.
     * @param condition The specific condition.
     * @param stateNode The target state node.
     * @return The distance.
     */
    public double conditionDist(Condition condition, StateNode stateNode) {
        double tempDist = 0;
        String name = condition.getDeviceVar();
        String val = condition.getVariableVal();
        String nowVal = stateNode.findVar(condition.getDeviceName(), condition.getDeviceVar(),
                condition.getCheckTrust(), condition.getCheckPrivacy());
        try {
            double dVal = Double.parseDouble(val);
            double dNowVal = Double.parseDouble(nowVal);
            EnvInstance envInstance;
            if ((envInstance = stateNode.findEnvInstance(name)) != null) {
                tempDist = calcCondDistDouble(envInstance.getEnvDynamics(), dVal, dNowVal, condition.getCompare());
            }
            return tempDist;
        } catch (Exception e) {
            return (condition.getCompare().equals(Compare.EQUAL) && nowVal.equals(val)) ||
                    (condition.getCompare().equals(Compare.NO_EQUAL) && !nowVal.equals(val)) ? 1 : 0;
        }
    }

    /**
     * Calculates the distance for a condition with double type values.
     * @param envDynamics The environment dynamics.
     * @param dVal The target value of the condition.
     * @param dNowVal The current value of the condition.
     * @param compare The comparison operator for the condition.
     * @return The calculated distance for the condition.
     */
    private double calcCondDistDouble(EnvDynamics envDynamics, double dVal, double dNowVal, Compare compare) {
        double tempDist;
        double up = Double.parseDouble(envDynamics.getUpperBound());
        double low = Double.parseDouble(envDynamics.getLowerBound());
        if (dVal < dNowVal && Compare.judgeLE(compare)) {
            tempDist = 1 - Math.abs(dNowVal - dVal) / (up - dVal);
        } else if (dVal > dNowVal && Compare.judgeGE(compare)) {
            tempDist = 1 - Math.abs(dNowVal - dVal) / (dVal - low);
        } else if ((dVal != dNowVal) && !compare.equals(Compare.EQUAL)) {
            tempDist = 1;
        } else {
            tempDist = 0;
        }
        return tempDist;
    }

    /**
     * Calculates the weight based on the position in the sequence.
     * @param pos The position in the sequence.
     * @return The calculated weight.
     */
    private double weightCalc(int pos) {
        return powerMap.get(DETECTION_LAYER_NUM - pos) / powerMap.get(DETECTION_LAYER_NUM);
    }
}
