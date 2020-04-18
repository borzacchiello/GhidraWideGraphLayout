package borzacchiello.widegraphlayout;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.Point;
import java.util.*;

import javax.swing.Icon;

import generic.stl.Pair;
import ghidra.app.plugin.core.functiongraph.graph.FGEdge;
import ghidra.app.plugin.core.functiongraph.graph.FunctionGraph;
import ghidra.app.plugin.core.functiongraph.graph.layout.AbstractFGLayout;
import ghidra.app.plugin.core.functiongraph.graph.layout.FGLayout;
import ghidra.app.plugin.core.functiongraph.graph.layout.FGLayoutProvider;
import ghidra.app.plugin.core.functiongraph.graph.vertex.FGVertex;
import ghidra.graph.VisualGraph;
import ghidra.graph.viewer.layout.*;
import ghidra.graph.viewer.vertex.VisualGraphVertexShapeTransformer;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;
import resources.ResourceManager;

public class WideGraphLayoutProvider extends FGLayoutProvider {

	private static final String NAME = "Wide Code Layout";
	private static final double PADDING_JUNCTIONS = 25.0;
	private static final double OFFSET_JUNCTIONS = 5.0;

	@Override
	public String getLayoutName() {
		return NAME;
	}

	@Override
	public Icon getActionIcon() {
		return ResourceManager.loadImage("images/wide_graph.png");
	}

	@Override
	public int getPriorityLevel() {
		return 500;  // lower than default visualization
	}

	@Override
	public FGLayout getFGLayout(FunctionGraph graph, TaskMonitor monitor) throws CancelledException {
		return new WideGraphLayout(graph);
	}

	private class WideGraphLayout extends AbstractFGLayout {

		private HashMap<Integer, List<Node>> row_to_nodes;
		private HashMap<FGVertex, Node> vertex_to_node;
		private HashMap<FGEdge, List<Point>> junctions;
		private HashMap<Point, List<FGEdge>> rev_junctions;
		private HashMap<Pair<Integer, FGEdge>, Double> junctions_padding_x;
		private HashMap<Pair<Integer, FGEdge>, Double> junctions_padding_y;

		private int max_col = -1;

		protected WideGraphLayout(FunctionGraph graph) {
			super(graph, NAME);

			row_to_nodes = new HashMap<>();
			vertex_to_node = new HashMap<>();
			junctions = new HashMap<>();
			rev_junctions = new HashMap<>();
			junctions_padding_x = new HashMap<>();
			junctions_padding_y = new HashMap<>();

		}

		@Override
		protected AbstractVisualGraphLayout<FGVertex, FGEdge> createClonedFGLayout(FunctionGraph newGraph) {
			return new WideGraphLayout(newGraph);
		}

		@Override
		protected Point2D getVertexLocation(FGVertex v, Column col, Row<FGVertex> row, Rectangle bounds) {
			return getCenteredVertexLocation(v, col, row, bounds);
		}

		private void resetLayout() {
			row_to_nodes.clear();
			vertex_to_node.clear();
			junctions.clear();
			rev_junctions.clear();
			junctions_padding_x.clear();
			junctions_padding_y.clear();

			max_col = -1;
		}

		@Override
		protected GridLocationMap<FGVertex, FGEdge> performInitialGridLayout(VisualGraph<FGVertex, FGEdge> g)
				throws CancelledException {

			GridLocationMap<FGVertex, FGEdge> gridLocations = new GridLocationMap<>();

			Collection<FGVertex> vertices = g.getVertices();
			Collection<FGEdge> edges = g.getEdges();
			List<FGVertex> sorted_vertices = new ArrayList<>(vertices);
			Collections.sort(sorted_vertices, (v1, v2) -> v1.getVertexAddress().compareTo(v2.getVertexAddress()));

			if (sorted_vertices.size() == 0)
				return gridLocations;

			Iterator<FGVertex> it = sorted_vertices.iterator();
			FGVertex root = it.next();
			resetLayout();

			assignRows(g, root);
			assignCols(g, root);
			assignJunctions(edges);
			assignJunctionPadding();
			populateGrid(gridLocations);

			return gridLocations;
		}

		private void assignRows(VisualGraph<FGVertex, FGEdge> g, FGVertex root) {
			class Closure {
				void run(FGVertex vertex, Set<FGVertex> path, int row) {
					Collection<FGVertex> successors = g.getSuccessors(vertex);

					if (!row_to_nodes.containsKey(row))
						row_to_nodes.put(row, new ArrayList<Node>());

					Node n;
					if (vertex_to_node.containsKey(vertex)) {
						n = vertex_to_node.get(vertex);
						if (n.row < row && !path.contains(n.v)) {
							List<Node> list_row = row_to_nodes.get(n.row);
							boolean res = list_row.remove(n);
							assert (res);
							row_to_nodes.get(row).add(n);
							n.row = row;
						}
					} else {
						n = new Node(vertex, row, 0);
						vertex_to_node.put(vertex, n);
						row_to_nodes.get(row).add(n);
					}

					if (path.contains(n.v))
						return;

					for (FGVertex s : successors) {
						HashSet<FGVertex> new_path = new HashSet<>(path);
						new_path.add(vertex);
						run(s, new_path, row + 1);
					}
				}
			}
			new Closure().run(root, new HashSet<FGVertex>(), 0);
		}

		private void assignCols(VisualGraph<FGVertex, FGEdge> g, FGVertex root) {
			class Closure {
				int assignScores(Set<FGVertex> visited, Map<FGVertex, Integer> vertex_scores, Set<FGVertex> path,
						FGVertex vertex, int score) {
					if (visited.contains(vertex)) {
						if (path.contains(vertex))
							return -1000;
						return 0;
					}

					visited.add(vertex);

					int subnodes = 0;
					Collection<FGVertex> successors = g.getSuccessors(vertex);
					for (FGVertex s : successors) {
						HashSet<FGVertex> new_path = new HashSet<>(path);
						new_path.add(vertex);
						subnodes += assignScores(visited, vertex_scores, new_path, s, score + subnodes + 1);
					}
					vertex_scores.put(vertex, score + subnodes + 1);
					return subnodes;
				}
			}
			HashMap<FGVertex, Integer> vertex_scores = new HashMap<>();
			new Closure().assignScores(new HashSet<FGVertex>(), vertex_scores, new HashSet<FGVertex>(), root, 0);

			int max_row_len = Collections.max(row_to_nodes.values(), (l1, l2) -> l1.size() - l2.size()).size();
			for (Integer row : row_to_nodes.keySet()) {
				List<Node> nodes_row = row_to_nodes.get(row);
				Collections.sort(nodes_row, (n1, n2) -> vertex_scores.get(n1.v) - vertex_scores.get(n2.v));

				int row_len = nodes_row.size();
				int offset = (max_row_len - row_len) / 2;

				int i = 0;
				for (Node n : nodes_row) {
					n.col = offset + i;
					if (n.col > max_col)
						max_col = n.col;
					i += 1;
				}
			}

		}

		private int getEdgeRow(int vertexRow) {
			return 2 * vertexRow + 1;
		}

		private int getEdgeCol(int vertexCol) {
			return 2 * vertexCol + 1;
		}

		private int getBottomEdgeRow(int vertexRow) {
			return 2 * vertexRow + 2;
		}

		private int getTopEdgeRow(int vertexRow) {
			return 2 * vertexRow;
		}

		private int getRightEdgeCol(int vertexCol) {
			return 2 * vertexCol + 2;
		}

		private int getLeftEdgeCol(int vertexCol) {
			return 2 * vertexCol;
		}

		private int sortEdges(FGEdge e1, FGEdge e2) {
			int src_row_1 = vertex_to_node.get(e1.getStart()).row;
			int src_col_1 = vertex_to_node.get(e1.getStart()).col;
			int dst_row_1 = vertex_to_node.get(e1.getEnd()).row;
			int dst_col_1 = vertex_to_node.get(e1.getEnd()).col;

			int src_row_2 = vertex_to_node.get(e2.getStart()).row;
			int src_col_2 = vertex_to_node.get(e2.getStart()).col;
			int dst_row_2 = vertex_to_node.get(e2.getEnd()).row;
			int dst_col_2 = vertex_to_node.get(e2.getEnd()).col;

			if (dst_row_1 < dst_row_2)
				return -1;
			else if (dst_row_2 < dst_row_1)
				return 1;

			if (src_row_1 > src_row_2)
				return -1;
			else if (src_row_2 > src_row_1)
				return 1;

			if (dst_col_1 < dst_col_2)
				return -1;
			else if (dst_col_2 < dst_col_1)
				return 1;

			if (src_col_1 < src_col_2)
				return -1;
			else if (src_col_2 < src_col_1)
				return 1;

			return 0;
		}

		private boolean detectEdgeClash(Collection<FGEdge> edges, FGEdge edge) {
			/*
			 * there exist an edge: -> its source is above me or on the right (edge.src.row
			 * < row || (edge.src.row == row && edge.src.col > col))
			 * 
			 * -> its destination is on my row, on the left (edge.dst.row == row &&
			 * edge.dst.col < col)
			 */

			FGVertex v_src = edge.getStart();
			FGVertex v_dst = edge.getEnd();
			Node v_src_n = vertex_to_node.get(v_src);
			Node v_dst_n = vertex_to_node.get(v_dst);

			for (FGEdge e : edges) {
				FGVertex src = e.getStart();
				FGVertex dst = e.getEnd();
				Node src_n = vertex_to_node.get(src);
				Node dst_n = vertex_to_node.get(dst);

				if (src_n.row < v_src_n.row || (src_n.row == v_src_n.row && src_n.col > v_src_n.col)) {
					if (dst_n.row == v_src_n.row + 1 && dst_n.col <= v_src_n.col) {
						return true;
					}
				}
			}
			return false;
		}

		private int maxColInRange(int row_min, int row_max) {
			// slow AF. Refactor it
			int col_max = 0;
			for (FGVertex v : vertex_to_node.keySet()) {
				Node n = vertex_to_node.get(v);
				if (n.row >= row_min && n.row <= row_max)
					if (n.col > col_max)
						col_max = n.col;
			}
			return col_max;
		}

		private void addJunction(Point p, FGEdge e) {
			if (!junctions.containsKey(e))
				junctions.put(e, new ArrayList<Point>());
			junctions.get(e).add(new Point(p));
			if (!rev_junctions.containsKey(p))
				rev_junctions.put(p, new ArrayList<FGEdge>());
			rev_junctions.get(p).add(e);
		}

		private void assignJunctions(Collection<FGEdge> edges) {
			ArrayList<FGEdge> sorted_edges = new ArrayList<FGEdge>(edges);
			Collections.sort(sorted_edges, (e1, e2) -> {
				return sortEdges(e1, e2);
			});

			for (FGEdge e : edges) {
				FGVertex startVertex = e.getStart();
				FGVertex endVertex = e.getEnd();
				Node startNode = vertex_to_node.get(startVertex);
				Node endNode = vertex_to_node.get(endVertex);

				Direction x, y;
				if (startNode.col == endNode.col)
					x = Direction.STILL;
				else if (startNode.col < endNode.col)
					x = Direction.RIGHT;
				else
					x = Direction.LEFT;

				if (startNode.row == endNode.row)
					y = Direction.STILL;
				else if (startNode.row > endNode.row)
					y = Direction.UP;
				else
					y = Direction.DOWN;

				if (y == Direction.DOWN) {
					// descend right, if clash, try descend left
					int start_row = startNode.row;
					int end_row = endNode.row;

					// descend at junction level
					Point p0 = new Point(getEdgeCol(startNode.col), getBottomEdgeRow(startNode.row));
					addJunction(p0, e);

					if (end_row - start_row > 1) {
						// if multiple levels, turn around the right column. If clash, turn around the
						// left column
						if (detectEdgeClash(edges, e)) {
							Point p1 = new Point(getLeftEdgeCol(endNode.col), getBottomEdgeRow(startNode.row));
							addJunction(p1, e);

							Point p2 = new Point(getLeftEdgeCol(endNode.col), getTopEdgeRow(endNode.row));
							addJunction(p2, e);
						} else {
							int col = getRightEdgeCol(maxColInRange(startNode.row, endNode.row));
							Point p1 = new Point(col, getBottomEdgeRow(startNode.row));
							addJunction(p1, e);

							Point p2 = new Point(col, getTopEdgeRow(endNode.row));
							addJunction(p2, e);
						}
					}

					// descend
					Point p3 = new Point(getEdgeCol(endNode.col), getTopEdgeRow(endNode.row));
					addJunction(p3, e);

				} else {
					// ascend always left
					x = Direction.LEFT;

					// descend at junction level
					Point p0 = new Point(getEdgeCol(startNode.col), getBottomEdgeRow(startNode.row));
					addJunction(p0, e);

					// go to destination left column
					Point p1 = new Point(getLeftEdgeCol(endNode.col), getBottomEdgeRow(startNode.row));
					addJunction(p1, e);

					// go up to vertedEnd row
					Point p2 = new Point(getLeftEdgeCol(endNode.col), getTopEdgeRow(endNode.row));
					addJunction(p2, e);

					// descend
					Point p3 = new Point(getEdgeCol(endNode.col), getTopEdgeRow(endNode.row));
					addJunction(p3, e);

					e.setEmphasis(0.2);
				}
			}
		}

		private void assignJunctionPadding() {
			// slow AF. Rewrite this
			Map<Integer, Set<FGEdge>> edge_per_row = new HashMap<>();
			Map<Integer, Set<FGEdge>> edge_per_col = new HashMap<>();

			for (Point p : rev_junctions.keySet()) {
				if (!edge_per_row.containsKey(p.x))
					edge_per_row.put(p.x, new HashSet<>());
				edge_per_row.get(p.x).addAll(rev_junctions.get(p));

				if (!edge_per_col.containsKey(p.y))
					edge_per_col.put(p.y, new HashSet<>());
				edge_per_col.get(p.y).addAll(rev_junctions.get(p));
			}

			for (int x : edge_per_row.keySet()) {

				Map<Integer, Integer> off_map = new HashMap<>();

				Pair<Integer, FGEdge> off;
				List<FGEdge> sorted_edges_per_row = new ArrayList<>(edge_per_row.get(x));
				Collections.sort(sorted_edges_per_row, (e1, e2) -> {
					return sortEdges(e1, e2);
				});
				for (FGEdge e : sorted_edges_per_row) {
					List<Point> junctions_edge = junctions.get(e);

					int min_col = Integer.MAX_VALUE;
					int max_col = 0;
					for (Point p : junctions_edge) {
						if (p.x != x)
							continue;
						if (p.y <= min_col)
							min_col = p.y;
						if (p.y >= max_col)
							max_col = p.y;
					}

					int v = 0;
					for (int i = min_col; i <= max_col; ++i) {
						if (!off_map.containsKey(i))
							off_map.put(i, 0);
						int off_v = off_map.get(i);
						if (off_v >= v)
							v = off_v + 1;
					}
					for (int i = min_col; i <= max_col; ++i)
						off_map.put(i, v);
					off = new Pair<>(x, e);
					junctions_padding_x.put(off, -PADDING_JUNCTIONS + v * OFFSET_JUNCTIONS);
				}
			}

			for (int y : edge_per_col.keySet()) {
				Map<Integer, Integer> off_map = new HashMap<>();

				Pair<Integer, FGEdge> off;
				List<FGEdge> sorted_edges_per_col = new ArrayList<>(edge_per_col.get(y));
				Collections.sort(sorted_edges_per_col, (e1, e2) -> {
					return sortEdges(e1, e2);
				});
				for (FGEdge e : sorted_edges_per_col) {
					List<Point> junctions_edge = junctions.get(e);

					int min_row = Integer.MAX_VALUE;
					int max_row = 0;
					for (Point p : junctions_edge) {
						if (p.y != y)
							continue;
						if (p.x <= min_row)
							min_row = p.x;
						if (p.x >= max_row)
							max_row = p.x;
					}

					int v = 0;
					for (int i = min_row; i <= max_row; ++i) {
						if (!off_map.containsKey(i))
							off_map.put(i, 0);
						int off_v = off_map.get(i);
						if (off_v >= v)
							v = off_v + 1;
					}
					for (int i = min_row; i <= max_row; ++i)
						off_map.put(i, v);
					off = new Pair<>(y, e);
					junctions_padding_y.put(off, -PADDING_JUNCTIONS + v * OFFSET_JUNCTIONS);
				}
			}

		}

		private void populateGrid(GridLocationMap<FGVertex, FGEdge> gridLocations) {
			for (Node n : vertex_to_node.values())
				gridLocations.set(n.v, n.row * 2 + 1, n.col * 2 + 1);

			for (FGEdge e : junctions.keySet()) {
				gridLocations.setArticulations(e, junctions.get(e));
			}
		}

		@Override
		protected Map<FGEdge, List<Point2D>> positionEdgeArticulationsInLayoutSpace(
				VisualGraphVertexShapeTransformer<FGVertex> transformer, Map<FGVertex, Point2D> vertexLayoutLocations,
				Collection<FGEdge> edges, LayoutLocationMap<FGVertex, FGEdge> layoutLocations)
				throws CancelledException {

			// uncomment for debug grid
//			VisualGraphRenderer.DEBUG_ROW_COL_MAP.put(getVisualGraph(), layoutLocations.copy());

			ArrayList<FGEdge> sorted_edges = new ArrayList<FGEdge>(edges);
			Collections.sort(sorted_edges, (e1, e2) -> {
				return sortEdges(e1, e2);
			});

			Map<FGEdge, List<Point2D>> newEdgeArticulations = new HashMap<>();

			for (FGEdge edge : sorted_edges) {
				monitor.checkCanceled();
				List<Point2D> newArticulations = new ArrayList<>();
				List<Point> edge_junctions = junctions.get(edge);

				int i = 0;
				for (Point gridPoint : layoutLocations.articulations(edge)) {

					Row<FGVertex> row = layoutLocations.row(gridPoint.y);
					Column column = layoutLocations.col(gridPoint.x);

					Point junction = edge_junctions.get(i);
					double row_padding = junctions_padding_x.get(new Pair<>(junction.x, edge));
					double col_padding = junctions_padding_y.get(new Pair<>(junction.y, edge));

					Point2D location = getCenteredEdgeLocation(column, row);
					Point2D new_loc = new Point2D.Double(location.getX() + row_padding, location.getY() + col_padding);
					newArticulations.add(new_loc);
					i++;
				}

				// fix first and last junction
				Point2D first_junction = newArticulations.get(0);
				Point2D last_junction = newArticulations.get(newArticulations.size() - 1);
				newArticulations.add(0,
						new Point2D.Double(first_junction.getX(), vertexLayoutLocations.get(edge.getStart()).getY()));
				newArticulations
						.add(new Point2D.Double(last_junction.getX(), vertexLayoutLocations.get(edge.getEnd()).getY()));

				newEdgeArticulations.put(edge, newArticulations);
			}
			return newEdgeArticulations;
		}

		@Override
		protected boolean isCondensedLayout() {
			return false;
		}

		private Direction oppositeDirection(Direction d) {
			switch (d) {
			case STILL:
				return Direction.STILL;
			case UP:
				return Direction.DOWN;
			case DOWN:
				return Direction.UP;
			case LEFT:
				return Direction.RIGHT;
			case RIGHT:
				return Direction.LEFT;
			}
			throw new IllegalArgumentException();
		}

	}

	private enum Direction {
		STILL, LEFT, RIGHT, DOWN, UP
	}

	private class Node {

		private FGVertex v;

		private int row;
		private int col;

		Node(FGVertex v, int row, int col) {
			this.v = v;
			this.row = row;
			this.col = col;
		}

		Node(FGVertex v) {
			this.v = v;
			this.row = 0;
			this.col = 0;
		}

		public int hashCode() {
			return (int) this.v.getVertexAddress().getOffset();
		}

		public boolean equals(Object o) {
			if (o.getClass() != this.getClass())
				return false;
			Node on = (Node) o;
			return this.v.getVertexAddress().getOffset() == on.v.getVertexAddress().getOffset();
		}

		@Override
		public String toString() {
			return "Node [ " + v + ", " + row + ", " + col + " ]";
		}
	}
}
