/*
 * boogieamp - Parser, Factory, and Utilities to create Boogie Programs from Java
 * Copyright (C) 2013 Martin Schaef and Stephan Arlt
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package typechecker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import util.Log;
import boogie.ast.ArrayLHS;
import boogie.ast.Body;
import boogie.ast.LeftHandSide;
import boogie.ast.Unit;
import boogie.ast.VarList;
import boogie.ast.VariableLHS;
import boogie.declaration.Declaration;
import boogie.declaration.Implementation;
import boogie.declaration.ProcedureDeclaration;
import boogie.declaration.ProcedureOrImplementationDeclaration;
import boogie.declaration.VariableDeclaration;
import boogie.specification.ModifiesSpecification;
import boogie.specification.Specification;
import boogie.statement.AssignmentStatement;
import boogie.statement.CallStatement;
import boogie.statement.HavocStatement;
import boogie.statement.IfStatement;
import boogie.statement.Statement;
import boogie.statement.WhileStatement;

/**
 * @author schaef
 * 
 */
public class ModifiesClauseConstruction {

	public static void createModifiesClause(Unit root) {
		Log.info("Warning: as the AST is immutable, computing the Modifies "
				+ "Clauses creates a new AST, so all your pointers to Procedures "
				+ "become invalid. If you use this, you have to continue working "
				+ "on the CFG.");
		ModifiesClauseConstruction instance = new ModifiesClauseConstruction();
		// unify procedure declarations and implementations
//		HashSet<Declaration> unifieddecls = new HashSet<Declaration>();
//		HashMap<String, ProcedureDeclaration> merged_procedures = new HashMap<String, ProcedureDeclaration>();
//		for (Declaration c : root.getDeclarations()) {
//			if (c instanceof ProcedureDeclaration) {
//				ProcedureDeclaration p = (ProcedureDeclaration) c;
//				if (!merged_procedures.containsKey(p.getIdentifier())) {
//					merged_procedures.put(p.getIdentifier(), p);
//				} else {
//					merged_procedures
//							.put(p.getIdentifier(),
//									mergeProcedures(merged_procedures.get(p
//											.getIdentifier()), p));
//				}
//			} else {
//				unifieddecls.add(c);
//			}
//		}
//		unifieddecls.addAll(merged_procedures.values());
//		root.setDeclarations(unifieddecls.toArray(new Declaration[unifieddecls
//				.size()]));

		HashMap<String, ProcedureDeclaration> proceduredecls = new HashMap<String, ProcedureDeclaration>(); 
		HashMap<String, LinkedList<Implementation>> implementations = new HashMap<String, LinkedList<Implementation>>();
		
		// collect the names of all global variables.
		for (Declaration c : root.getDeclarations()) {
			if (c instanceof VariableDeclaration) {
				VariableDeclaration var = (VariableDeclaration) c;
				for (VarList v : var.getVariables()) {
					for (int i = 0; i < v.getIdentifiers().length; i++) {
						instance.globalIdentifier.add(v.getIdentifiers()[i]);
					}
				}
			} else if (c instanceof ProcedureDeclaration) {
				ProcedureDeclaration decl = (ProcedureDeclaration)c;
				if (proceduredecls.containsKey(decl.getIdentifier())) {
					throw new RuntimeException("Double declaration of procedure "+decl.getIdentifier());
				}
				proceduredecls.put(decl.getIdentifier(), decl);
			} else if (c instanceof Implementation) {
				Implementation impl = (Implementation)c;
				if (!implementations.containsKey(impl.getIdentifier())) {
					implementations.put(impl.getIdentifier(), new LinkedList<Implementation>());
				}
				implementations.get(impl.getIdentifier()).add(impl);
			}
		}		
		
		// now build the non-transitive modifies set for each
		// procedure
		for (Declaration c : root.getDeclarations()) {
			if (c instanceof ProcedureOrImplementationDeclaration) {
				ProcedureOrImplementationDeclaration p = (ProcedureOrImplementationDeclaration) c;
				instance.computeModifiedGlobalsAndCalls(p);
			}
		}

		// now build the transitive modifies set for each
		// procedure
		for (Declaration c : root.getDeclarations()) {
			if (c instanceof ProcedureOrImplementationDeclaration) {
				ProcedureOrImplementationDeclaration p = (ProcedureOrImplementationDeclaration) c;
				instance.computeTransitiveModifies(p.getIdentifier(),
						new HashSet<String>());
			}
		}

		// now create new procedure declarations with the
		// updated modifies clauses.
		LinkedList<Declaration> newdecls = new LinkedList<Declaration>();
		for (Declaration c : root.getDeclarations()) {
			if (c instanceof ProcedureDeclaration) {
				ProcedureDeclaration p = (ProcedureDeclaration) c;
				HashSet<Specification> newspec = new HashSet<Specification>();
				if (p.getSpecification() != null) {
					for (Specification spec : p.getSpecification()) {
						// preserve everything but the modifes clauses.
						if (!(spec instanceof ModifiesSpecification)) {
							newspec.add(spec);
						}
					}
				}
				HashSet<String> identifiers = instance.procedureInfoMap.get(p
						.getIdentifier()).modifiedGlobals;
				ModifiesSpecification modspec = new ModifiesSpecification(
						p.getLocation(), false,
						identifiers.toArray(new String[identifiers.size()]));
				newspec.add(modspec);
				ProcedureDeclaration newproc = new ProcedureDeclaration(p.getLocation(),
						p.getAttributes(), p.getIdentifier(),
						p.getTypeParams(), p.getInParams(), p.getOutParams(),
						newspec.toArray(new Specification[newspec.size()]),
						p.getBody());
				newdecls.add(newproc);
			} else {
				newdecls.add(c);
			}
		}
		// write the new decls to the root node.
		root.setDeclarations(newdecls.toArray(new Declaration[newdecls.size()]));
	}


	private HashSet<String> globalIdentifier = new HashSet<String>();

	private class ProcedureInfo {
		public HashSet<String> localVariables = new HashSet<String>();
		public HashSet<String> modifiedGlobals = new HashSet<String>();
		public HashSet<String> calledProcedures = new HashSet<String>();
	}

	private HashMap<String, ProcedureInfo> procedureInfoMap = new HashMap<String, ProcedureInfo>();

	private void computeModifiedGlobalsAndCalls(ProcedureOrImplementationDeclaration p) {
		if (!this.procedureInfoMap.containsKey(p.getIdentifier())) {			
			this.procedureInfoMap.put(p.getIdentifier(), new ProcedureInfo());			
		}
		ProcedureInfo pi = this.procedureInfoMap.get(p.getIdentifier());
		Body body = p.getBody();
		if (body == null) {
			return;
		}

		computeModifiedGlobalsAndCalls(body.getBlock(), pi);
	}

	private void computeModifiedGlobalsAndCalls(Statement[] statements,
			ProcedureInfo pi) {
		for (Statement s : statements) {
			if (s instanceof AssignmentStatement) {
				AssignmentStatement assignstatement = (AssignmentStatement) s;
				for (LeftHandSide lhs : assignstatement.getLhs()) {
					computeModifiedGlobalsAndCalls(lhs, pi);
				}
			} else if (s instanceof HavocStatement) {
				HavocStatement havocstmt = (HavocStatement) s;
				for (String str : havocstmt.getIdentifiers()) {
					if (this.globalIdentifier.contains(str)) {
						pi.modifiedGlobals.add(str);
					}
				}
			} else if (s instanceof CallStatement) {
				CallStatement callstatement = (CallStatement) s;
				pi.calledProcedures.add(callstatement.getMethodName());
			} else if (s instanceof IfStatement) {
				IfStatement ifstmt = (IfStatement) s;
				computeModifiedGlobalsAndCalls(ifstmt.getThenPart(), pi);
				computeModifiedGlobalsAndCalls(ifstmt.getElsePart(), pi);
			} else if (s instanceof WhileStatement) {
				WhileStatement whilestmt = (WhileStatement) s;
				computeModifiedGlobalsAndCalls(whilestmt.getBody(), pi);
			} else {
				// do nothing
			}
		}
	}

	private void computeModifiedGlobalsAndCalls(LeftHandSide lhs,
			ProcedureInfo pi) {
		if (lhs instanceof ArrayLHS) {
			ArrayLHS alhs = (ArrayLHS) lhs;
			computeModifiedGlobalsAndCalls(alhs.getArray(), pi);
		} else if (lhs instanceof VariableLHS) {
			VariableLHS vlhs = (VariableLHS) lhs;
			if (!pi.localVariables.contains(vlhs.getIdentifier())
					&& this.globalIdentifier.contains(vlhs.getIdentifier())) {
				pi.modifiedGlobals.add(vlhs.getIdentifier());
			}
		}
	}

	// --------- transitive computation -----------
	/**
	 * recomputes recursively the transitive set of modified globals for a
	 * procedure
	 * 
	 * @param identifier
	 *            the name of the procedure
	 * @param visited
	 *            bookkeeping of visited procedures to avoid endless loops
	 * @return the new (transitive) set of modified globals
	 */
	private HashSet<String> computeTransitiveModifies(String identifier,
			HashSet<String> visited) {
		ProcedureInfo pi = this.procedureInfoMap.get(identifier);
		if (visited.contains(identifier)) {
			return pi.modifiedGlobals;
		}
		visited.add(identifier);
		for (String callee : pi.calledProcedures) {
			pi.modifiedGlobals.addAll(this.computeTransitiveModifies(callee,
					visited));
		}
		return pi.modifiedGlobals;
	}

}
