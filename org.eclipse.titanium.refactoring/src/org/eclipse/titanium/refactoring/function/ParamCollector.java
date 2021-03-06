/******************************************************************************
 * Copyright (c) 2000-2016 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.eclipse.titanium.refactoring.function;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;
import org.eclipse.titan.common.logging.ErrorReporter;
import org.eclipse.titan.designer.AST.ASTVisitor;
import org.eclipse.titan.designer.AST.Assignment;
import org.eclipse.titan.designer.AST.Assignment.Assignment_type;
import org.eclipse.titan.designer.AST.ISubReference;
import org.eclipse.titan.designer.AST.IVisitableNode;
import org.eclipse.titan.designer.AST.Location;
import org.eclipse.titan.designer.AST.Module;
import org.eclipse.titan.designer.AST.Reference;
import org.eclipse.titan.designer.AST.ReferenceFinder;
import org.eclipse.titan.designer.AST.ReferenceFinder.Hit;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Def_Const;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Def_ModulePar;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Def_Timer;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Def_Var;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Def_Var_Template;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Definition;
import org.eclipse.titan.designer.AST.TTCN3.definitions.FormalParameter;
import org.eclipse.titan.designer.AST.TTCN3.values.Undefined_LowerIdentifier_Value;
import org.eclipse.titan.designer.parsers.CompilationTimeStamp;

/**
 * This class is only instantiated by the ExtractToFunctionRefactoring once per each refactoring operation.
 * By calling <code>perform()</code>, a list of parameters (<code>Param</code>) is created from a list of statements (StatementList node).
 * 
 * @author Viktor Varga
 */
class ParamCollector {
	
	//in
	private final IProject project;
	private final StatementList selectedStatements;
	private final Module selectedModule;
	
	//out
	private List<Param> params;
	private boolean removeReturnClause = false;
	private final List<RefactoringStatusEntry> warnings;
	
	ParamCollector(IProject project, StatementList selectedStatements, Module selectedModule) {
		this.selectedStatements = selectedStatements;
		this.selectedModule = selectedModule;
		this.project = project;
		warnings = new ArrayList<RefactoringStatusEntry>();
	}
	
	List<RefactoringStatusEntry> getWarnings() {
		return warnings;
	}
	List<Param> getParams() {
		return params;
	}
	boolean isRemoveReturnClause() {
		return removeReturnClause;
	}
	
	void perform() {
		//collect params
		params = new ArrayList<Param>();
		ParamFinder visitor = new ParamFinder();
		selectedStatements.accept(visitor);
		if (ExtractToFunctionRefactoring.DEBUG_MESSAGES_ON) {
			ErrorReporter.logError(visitor.createDebugInfo());
		}
		if (ExtractToFunctionRefactoring.DEBUG_MESSAGES_ON) {
			ErrorReporter.logError(createDebugInfo());
		}
	}
	
	private String createDebugInfo() {
		StringBuilder sb = new StringBuilder();
		sb.append("ExtractToFunctionRefactoring->ParamCollector debug info: \n");
		for (Param p: params) {
			sb.append(p.createDebugInfo());
			sb.append("\n");
		}
		return sb.toString();
	}

	/**
	 * Collects definitions from outside of the StatementList. Call for a StatementList.
	 * */
	private class ParamFinder extends ASTVisitor {
		
		private static final boolean DEBUG = ExtractToFunctionRefactoring.DEBUG_MESSAGES_ON;
		private StatementList toVisit;
		private StringBuilder debugInfo = new StringBuilder();
		
		@Override
		public int visit(IVisitableNode node) {
			if (node instanceof StatementList) {
				toVisit = (StatementList)node;
			}
			if (node instanceof Undefined_LowerIdentifier_Value) {
				((Undefined_LowerIdentifier_Value)node).getAsReference();
				return V_CONTINUE;
			}
			if (node instanceof Reference) {
				Reference ref = (Reference)node;
				Assignment as = ref.getRefdAssignment(CompilationTimeStamp.getBaseTimestamp(), false);
				if (as instanceof Definition && isGoodDefType(as) && !isInsideRange(as.getLocation())) {
					Definition def = (Definition)as;
					//def is outside of the selection
					//TODO: does ReferenceFinder handle Undefined_LowerIdentifier_Values?
					
					//DO NOT use ReferenceFinder.scope, because it returns an incorrect scope for formal parameters
					ReferenceFinder refFinder = new ReferenceFinder(def);
					Map<Module, List<Hit>> refs = refFinder.findAllReferences(selectedModule, project, null, false);
					if (!def.isLocal()) {
						//module parameter, ... -> no param created
						if (DEBUG) {
							debugInfo.append("  ").append(def.getIdentifier()).append(" -> is global (isLocal:")
							.append(def.isLocal()).append(" ), no param created\n");
						}
						return V_CONTINUE;
					}
					//def is in the same statement block
					Assignment_type at = null;
					if (def instanceof FormalParameter) {
						at = ((FormalParameter)def).getAssignmentType();
					}
					Param p = new Param();
					p.setDef((Definition)as);
					p.setDeclaredInside(false);
					p.setName(as.getIdentifier().toString());
					if (params.contains(p)) {
						return V_CONTINUE;
					} else {
						params.add(p);
					}
					p.setRefs(getRefsInRange(selectedModule, refs));
					boolean refsBeyondSelection = isAnyRefsAfterLocation(selectedModule, selectedStatements.getLocation(), refs);
					if (at == Assignment_type.A_PAR_TEMP_INOUT || at == Assignment_type.A_PAR_TEMP_OUT
							|| at == Assignment_type.A_PAR_VAL_INOUT || at == Assignment_type.A_PAR_VAL_OUT
							|| refsBeyondSelection) {
						//def is a formal inout/out parameter or it is referred after the selection -> it becomes an inout param
						p.setPassingType(ArgumentPassingType.INOUT);
						if (DEBUG) {
							debugInfo.append("  ").append(def.getIdentifier()).append(" -> INOUT param\n");
						}
					} else {
						//def is not referred after the selection and it is not an in/inout formal parameter -> it becomes an in param
						p.setPassingType(ArgumentPassingType.IN);
						if (DEBUG) {
							debugInfo.append("  ").append(def.getIdentifier()).append(" -> IN param\n");
						}
					}
				}
			} else if (node instanceof Definition) {
				Definition def = (Definition)node;
				if (isGoodDefType(def)) {
					//variables declared inside the selection
					ReferenceFinder refFinder = new ReferenceFinder(def);
					Map<Module, List<Hit>> refs = refFinder.findAllReferences(selectedModule, project, null, false);
					boolean refsOutside = isAnyReferenceOutsideRange(selectedModule, refs);
					if (!refsOutside) {
						if (DEBUG) {
							debugInfo.append("  ").append(def.getIdentifier()).append(" -> local var, no param created\n");
						}
						//variable has no refs outside, so it will be a new local variable
						return V_CONTINUE;
					}
					Param p = new Param();
					p.setDef(def);
					p.setDeclaredInside(true);
					p.setName(def.getIdentifier().toString());
					if (params.contains(p)) {
						return V_CONTINUE;
					} else {
						params.add(p);
					}
					List<ISubReference> refsInRange = getRefsInRange(selectedModule, refs);
					if (isDefWithoutInit(def) && refsInRange.isEmpty()) {
						//variable has no refs inside, and its declaration (inside) has no init part
						if (DEBUG) {
							debugInfo.append("  ").append(def.getIdentifier()).append(" -> no refs inside and no init part, def moved out and no param created\n");
						}
						p.setPassingType(ArgumentPassingType.NONE);
					} else {
						//variable has refs inside or its declaration (inside) has an init part -> out param
						if (DEBUG) {
							debugInfo.append("  ").append(def.getIdentifier()).append(" -> OUT param, declaration is moved out\n");
						}
						p.setPassingType(ArgumentPassingType.OUT);
					}
					p.setRefs(refsInRange);
				}
			}
			return V_CONTINUE;
		}
		
		public String createDebugInfo() {
			StringBuilder sb = new StringBuilder();
			sb.append("ExtractToFunctionRefactoring->ParamCollector debug info: \n");
			sb.append(debugInfo);
			return sb.toString();
		}
		
		private boolean isGoodDefType(IVisitableNode node) {
			if (node instanceof Def_Var ||
					node instanceof Def_Const ||
					node instanceof Def_ModulePar ||
					node instanceof FormalParameter ||
					node instanceof Def_Var_Template ||
					node instanceof Def_Timer) {
				return true;
			}
			return false;
		}
		
		/**
		 * @return whether there are any refs that are located in <code>locationModule</code> beyond location <code>loc</code>
		 */
		private boolean isAnyRefsAfterLocation(Module locationModule, Location loc, Map<Module, List<Hit>> refs) {
			for (Map.Entry<Module, List<Hit>> e: refs.entrySet()) {
				if (!e.getKey().equals(locationModule)) {
					continue;
				}
				List<Hit> hs = e.getValue();
				ListIterator<Hit> it = hs.listIterator();
				while (it.hasNext()) {
					Hit curr = it.next();
					if (curr.reference.getLocation().getEndOffset() > loc.getEndOffset()) {
						return true;
					}
				}
			}
			return false;
		}
		
		/**
		 * @return a list of references (subset of all references in <code>refs</code>) that are inside <code>toVisit</code> in <code>module</code>
		 */
		private List<ISubReference> getRefsInRange(Module module, Map<Module, List<Hit>> refs) {
			List<ISubReference> subrefs = new ArrayList<ISubReference>();
			for (Map.Entry<Module, List<Hit>> e: refs.entrySet()) {
				if (!e.getKey().equals(module)) {
					continue;
				}
				List<Hit> hs = e.getValue();
				ListIterator<Hit> it = hs.listIterator();
				while (it.hasNext()) {
					Hit curr = it.next();
					if (isInsideRange(curr.reference.getLocation())) {
						Reference r = curr.reference;
						if (r.getSubreferences().isEmpty()) {
							ErrorReporter.logError("ParamCollector::getRefsInRange(): No subrefs found in a reference! ");
							continue;
						}
						subrefs.add(r.getSubreferences().get(0));
					}
				}
			}
			return subrefs;
		}
		
		/**
		 * @return whether there are any references located outside of <code>toVisit</code> from the <code>refs</code> list
		 */
		private boolean isAnyReferenceOutsideRange(Module moduleOfRange, Map<Module, List<Hit>> refs) {
			if (toVisit == null) {
				ErrorReporter.logError("ParamFinderVisitor::isAnyReferenceOutsideRange(): StatementList 'toVisit' is null! ");
				return false;
			}
			for (Map.Entry<Module, List<Hit>> e: refs.entrySet()) {
				if (!e.getKey().equals(moduleOfRange)) {
					return true;
				}
				List<Hit> hs = e.getValue();
				for (Hit h: hs) {
					if (!isInsideRange(h.reference.getLocation())) {
						return true;
					}
				}
			}
			return false;
		}
		
		/**
		 * @return whether location <code>toCheck</code> is inside the location of <code>toVisit</code>
		 */
		private boolean isInsideRange(Location toCheck) {
			if (toVisit == null) {
				ErrorReporter.logError("ParamFinderVisitor::isInsideRange(): StatementList 'toVisit' is null! ");
				return false;
			}
			Location range = toVisit.getLocation();
			return toCheck.getOffset() >= range.getOffset() && toCheck.getEndOffset() <= range.getEndOffset();
		}
		
		/**
		 * @return whether Definition <code>toCheck</code> has no initialization part
		 */
		private boolean isDefWithoutInit(Definition toCheck) {
			if (toCheck == null) {
				return false;
			}
			return toCheck.getLocation().getEndOffset() == toCheck.getIdentifier().getLocation().getEndOffset();
		}
		
	}
	
}
